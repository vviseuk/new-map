package com.vvise.new_map.service

import com.vvise.new_map.dto.*
import com.vvise.new_map.entity.BaseLayer
import com.vvise.new_map.entity.Composition
import com.vvise.new_map.repository.*
import com.vvise.new_map.security.AuthenticatedUser
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class CompositionService(
    private val compositionRepository: CompositionRepository,
    private val baseLayerRepository: BaseLayerRepository,
    private val baseSegmentRepository: BaseSegmentRepository,
    private val overlayLayerRepository: OverlayLayerRepository,
    private val segmentKeyframeRepository: SegmentKeyframeRepository,
    private val segmentTransitionRepository: SegmentTransitionRepository,
    private val projectRepository: ProjectRepository,
    private val teamService: TeamService
) {

    @Transactional
    fun createComposition(
        teamId: UUID,
        projectId: UUID,
        request: CreateCompositionRequest?,
        user: AuthenticatedUser
    ): CompositionDto {
        if (!teamService.isTeamMember(teamId, user)) {
            throw IllegalAccessException("Not a member of this team")
        }

        val project = projectRepository.findByIdAndTeamIdAndDeletedFalse(projectId, teamId)
            ?: throw IllegalArgumentException("Project not found")

        if (compositionRepository.existsByProjectIdAndDeletedFalse(projectId)) {
            throw IllegalStateException("Composition already exists for this project")
        }

        val composition = Composition(
            project = project,
            name = request?.name ?: project.name,
            duration = request?.duration ?: 60.0,
            frameRate = request?.frameRate ?: 30,
            mapConfig = request?.mapConfig ?: emptyMap()
        )

        val savedComposition = compositionRepository.save(composition)

        // Create the base layer automatically
        val baseLayer = BaseLayer(
            composition = savedComposition,
            name = "Camera"
        )
        val savedBaseLayer = baseLayerRepository.save(baseLayer)

        return CompositionDto.from(
            composition = savedComposition,
            baseLayer = BaseLayerDto.from(savedBaseLayer, emptyList()),
            overlayLayers = emptyList()
        )
    }

    fun getComposition(teamId: UUID, projectId: UUID, user: AuthenticatedUser): CompositionDto? {
        if (!teamService.isTeamMember(teamId, user)) {
            return null
        }

        val project = projectRepository.findByIdAndTeamIdAndDeletedFalse(projectId, teamId)
            ?: return null

        val composition = compositionRepository.findByProjectIdAndDeletedFalse(projectId)
            ?: return null

        val baseLayer = baseLayerRepository.findByCompositionIdAndDeletedFalse(composition.id!!)
        val baseLayerDto = baseLayer?.let {
            val segments = baseSegmentRepository.findByBaseLayerIdAndDeletedFalseOrderByOrder(it.id!!)
                .map { segment -> BaseSegmentDto.from(segment) }
            BaseLayerDto.from(it, segments)
        }

        val overlayLayers = overlayLayerRepository.findByCompositionIdAndDeletedFalseOrderByOrder(composition.id!!)
            .map { layer ->
                val keyframes = segmentKeyframeRepository.findByLayerIdAndDeletedFalseOrderByTimeOffset(layer.id!!)
                    .map { LayerKeyframeDto.from(it) }
                val transition = segmentTransitionRepository.findByFromLayerIdAndDeletedFalse(layer.id!!)
                    ?.let { LayerTransitionDto.from(it) }
                val mapAssetDto = layer.mapAsset?.let { MapAssetDto.from(it) }
                OverlayLayerDto.from(layer, mapAssetDto, keyframes, transition)
            }

        return CompositionDto.from(composition, baseLayerDto, overlayLayers)
    }

    @Transactional
    fun updateComposition(
        teamId: UUID,
        projectId: UUID,
        request: UpdateCompositionRequest,
        user: AuthenticatedUser
    ): CompositionDto? {
        if (!teamService.isTeamMember(teamId, user)) {
            return null
        }

        projectRepository.findByIdAndTeamIdAndDeletedFalse(projectId, teamId)
            ?: return null

        val composition = compositionRepository.findByProjectIdAndDeletedFalse(projectId)
            ?: return null

        request.name?.let { composition.name = it }
        request.duration?.let { composition.duration = it }
        request.frameRate?.let { composition.frameRate = it }
        request.mapConfig?.let { composition.mapConfig = it }

        compositionRepository.save(composition)
        return getComposition(teamId, projectId, user)
    }

    @Transactional
    fun deleteComposition(teamId: UUID, projectId: UUID, user: AuthenticatedUser): Boolean {
        if (!teamService.isTeamAdmin(teamId, user)) {
            return false
        }

        projectRepository.findByIdAndTeamIdAndDeletedFalse(projectId, teamId)
            ?: return false

        val composition = compositionRepository.findByProjectIdAndDeletedFalse(projectId)
            ?: return false

        // Soft delete all related entities
        baseLayerRepository.findByCompositionIdAndDeletedFalse(composition.id!!)?.let { baseLayer ->
            baseSegmentRepository.findByBaseLayerIdAndDeletedFalseOrderByOrder(baseLayer.id!!).forEach { segment ->
                segment.softDelete()
                baseSegmentRepository.save(segment)
            }
            baseLayer.softDelete()
            baseLayerRepository.save(baseLayer)
        }

        overlayLayerRepository.findByCompositionIdAndDeletedFalseOrderByOrder(composition.id!!).forEach { layer ->
            segmentKeyframeRepository.findByLayerIdAndDeletedFalseOrderByTimeOffset(layer.id!!).forEach { kf ->
                kf.softDelete()
                segmentKeyframeRepository.save(kf)
            }
            segmentTransitionRepository.findByFromLayerIdAndDeletedFalse(layer.id!!)?.let { tr ->
                tr.softDelete()
                segmentTransitionRepository.save(tr)
            }
            layer.softDelete()
            overlayLayerRepository.save(layer)
        }

        composition.softDelete()
        compositionRepository.save(composition)
        return true
    }
}
