package com.vvise.new_map.service

import com.vvise.new_map.dto.*
import com.vvise.new_map.entity.OverlayLayer
import com.vvise.new_map.repository.*
import com.vvise.new_map.security.AuthenticatedUser
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class OverlayLayerService(
    private val compositionRepository: CompositionRepository,
    private val overlayLayerRepository: OverlayLayerRepository,
    private val mapAssetRepository: MapAssetRepository,
    private val segmentKeyframeRepository: SegmentKeyframeRepository,
    private val segmentTransitionRepository: SegmentTransitionRepository,
    private val projectRepository: ProjectRepository,
    private val teamService: TeamService
) {

    @Transactional
    fun addLayer(
        teamId: UUID,
        projectId: UUID,
        request: CreateOverlayLayerRequest,
        user: AuthenticatedUser
    ): OverlayLayerDto {
        if (!teamService.isTeamMember(teamId, user)) {
            throw IllegalAccessException("Not a member of this team")
        }

        projectRepository.findByIdAndTeamIdAndDeletedFalse(projectId, teamId)
            ?: throw IllegalArgumentException("Project not found")

        val composition = compositionRepository.findByProjectIdAndDeletedFalse(projectId)
            ?: throw IllegalArgumentException("Composition not found")

        val mapAsset = request.mapAssetId?.let {
            mapAssetRepository.findByIdAndTeamIdAndDeletedFalse(it, teamId)
                ?: throw IllegalArgumentException("Map asset not found")
        }

        val maxOrder = overlayLayerRepository.findByCompositionIdAndDeletedFalseOrderByOrder(composition.id!!)
            .maxOfOrNull { it.order } ?: -1

        val layer = OverlayLayer(
            composition = composition,
            mapAsset = mapAsset,
            name = request.name,
            order = maxOrder + 1,
            startTime = request.startTime ?: 0.0,
            endTime = request.endTime,
            visible = request.visible ?: true,
            locked = request.locked ?: false,
            opacity = request.opacity ?: 1.0,
            geometry = GeoJsonGeometry.toGeometry(request.geometry),
            styleOverrides = request.styleOverrides
        )

        val saved = overlayLayerRepository.save(layer)
        val mapAssetDto = mapAsset?.let { MapAssetDto.from(it) }
        return OverlayLayerDto.from(saved, mapAssetDto, emptyList(), null)
    }

    fun getLayers(teamId: UUID, projectId: UUID, user: AuthenticatedUser): List<OverlayLayerSummaryDto> {
        if (!teamService.isTeamMember(teamId, user)) {
            throw IllegalAccessException("Not a member of this team")
        }

        projectRepository.findByIdAndTeamIdAndDeletedFalse(projectId, teamId)
            ?: throw IllegalArgumentException("Project not found")

        val composition = compositionRepository.findByProjectIdAndDeletedFalse(projectId)
            ?: throw IllegalArgumentException("Composition not found")

        return overlayLayerRepository.findByCompositionIdAndDeletedFalseOrderByOrder(composition.id!!)
            .map { layer ->
                val keyframeCount = segmentKeyframeRepository.findByLayerIdAndDeletedFalseOrderByTimeOffset(layer.id!!).size
                OverlayLayerSummaryDto.from(layer, keyframeCount)
            }
    }

    fun getLayer(teamId: UUID, projectId: UUID, layerId: UUID, user: AuthenticatedUser): OverlayLayerDto? {
        if (!teamService.isTeamMember(teamId, user)) {
            return null
        }

        projectRepository.findByIdAndTeamIdAndDeletedFalse(projectId, teamId)
            ?: return null

        val composition = compositionRepository.findByProjectIdAndDeletedFalse(projectId)
            ?: return null

        val layer = overlayLayerRepository.findByIdAndCompositionIdAndDeletedFalse(layerId, composition.id!!)
            ?: return null

        val keyframes = segmentKeyframeRepository.findByLayerIdAndDeletedFalseOrderByTimeOffset(layer.id!!)
            .map { LayerKeyframeDto.from(it) }

        val transition = segmentTransitionRepository.findByFromLayerIdAndDeletedFalse(layer.id!!)
            ?.let { LayerTransitionDto.from(it) }

        val mapAssetDto = layer.mapAsset?.let { MapAssetDto.from(it) }

        return OverlayLayerDto.from(layer, mapAssetDto, keyframes, transition)
    }

    @Transactional
    fun updateLayer(
        teamId: UUID,
        projectId: UUID,
        layerId: UUID,
        request: UpdateOverlayLayerRequest,
        user: AuthenticatedUser
    ): OverlayLayerDto? {
        if (!teamService.isTeamMember(teamId, user)) {
            return null
        }

        projectRepository.findByIdAndTeamIdAndDeletedFalse(projectId, teamId)
            ?: return null

        val composition = compositionRepository.findByProjectIdAndDeletedFalse(projectId)
            ?: return null

        val layer = overlayLayerRepository.findByIdAndCompositionIdAndDeletedFalse(layerId, composition.id!!)
            ?: return null

        request.name?.let { layer.name = it }
        request.startTime?.let { layer.startTime = it }
        request.endTime?.let { layer.endTime = it }
        request.visible?.let { layer.visible = it }
        request.locked?.let { layer.locked = it }
        request.opacity?.let { layer.opacity = it }
        request.geometry?.let { layer.geometry = GeoJsonGeometry.toGeometry(it) }
        request.styleOverrides?.let { layer.styleOverrides = it }

        request.mapAssetId?.let { assetId ->
            val mapAsset = mapAssetRepository.findByIdAndTeamIdAndDeletedFalse(assetId, teamId)
            layer.mapAsset = mapAsset
        }

        overlayLayerRepository.save(layer)
        return getLayer(teamId, projectId, layerId, user)
    }

    @Transactional
    fun deleteLayer(
        teamId: UUID,
        projectId: UUID,
        layerId: UUID,
        user: AuthenticatedUser
    ): Boolean {
        if (!teamService.isTeamMember(teamId, user)) {
            return false
        }

        projectRepository.findByIdAndTeamIdAndDeletedFalse(projectId, teamId)
            ?: return false

        val composition = compositionRepository.findByProjectIdAndDeletedFalse(projectId)
            ?: return false

        val layer = overlayLayerRepository.findByIdAndCompositionIdAndDeletedFalse(layerId, composition.id!!)
            ?: return false

        // Soft delete keyframes
        segmentKeyframeRepository.findByLayerIdAndDeletedFalseOrderByTimeOffset(layer.id!!).forEach { kf ->
            kf.softDelete()
            segmentKeyframeRepository.save(kf)
        }

        // Soft delete transitions
        segmentTransitionRepository.findByFromLayerIdAndDeletedFalse(layer.id!!)?.let { tr ->
            tr.softDelete()
            segmentTransitionRepository.save(tr)
        }

        layer.softDelete()
        overlayLayerRepository.save(layer)
        return true
    }

    @Transactional
    fun reorderLayers(
        teamId: UUID,
        projectId: UUID,
        request: ReorderLayersRequest,
        user: AuthenticatedUser
    ): List<OverlayLayerSummaryDto> {
        if (!teamService.isTeamMember(teamId, user)) {
            throw IllegalAccessException("Not a member of this team")
        }

        projectRepository.findByIdAndTeamIdAndDeletedFalse(projectId, teamId)
            ?: throw IllegalArgumentException("Project not found")

        val composition = compositionRepository.findByProjectIdAndDeletedFalse(projectId)
            ?: throw IllegalArgumentException("Composition not found")

        request.layerIds.forEachIndexed { index, layerId ->
            val layer = overlayLayerRepository.findByIdAndCompositionIdAndDeletedFalse(layerId, composition.id!!)
            layer?.let {
                it.order = index
                overlayLayerRepository.save(it)
            }
        }

        return getLayers(teamId, projectId, user)
    }
}
