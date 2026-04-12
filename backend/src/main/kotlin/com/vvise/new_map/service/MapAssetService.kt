package com.vvise.new_map.service

import com.vvise.new_map.dto.*
import com.vvise.new_map.entity.MapAsset
import com.vvise.new_map.entity.MapAssetType
import com.vvise.new_map.repository.AssetCategoryRepository
import com.vvise.new_map.repository.MapAssetRepository
import com.vvise.new_map.repository.OverlayLayerRepository
import com.vvise.new_map.repository.TeamRepository
import com.vvise.new_map.security.AuthenticatedUser
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class MapAssetService(
    private val mapAssetRepository: MapAssetRepository,
    private val assetCategoryRepository: AssetCategoryRepository,
    private val overlayLayerRepository: OverlayLayerRepository,
    private val teamRepository: TeamRepository,
    private val teamService: TeamService
) {

    @Transactional
    fun createAsset(
        teamId: UUID,
        request: CreateMapAssetRequest,
        user: AuthenticatedUser
    ): MapAssetDto {
        if (!teamService.isTeamMember(teamId, user)) {
            throw IllegalAccessException("Not a member of this team")
        }

        val team = teamRepository.findByIdAndDeletedFalse(teamId)
            ?: throw IllegalArgumentException("Team not found")

        val category = request.categoryId?.let {
            assetCategoryRepository.findByIdAndTeamIdAndDeletedFalse(it, teamId)
        }

        val asset = MapAsset(
            team = team,
            category = category,
            name = request.name,
            description = request.description,
            type = request.type,
            styleData = request.styleData,
            defaultGeometry = request.defaultGeometry?.let { GeoJsonGeometry.toGeometry(it) },
            previewUrl = request.previewUrl,
            tags = request.tags
        )

        val saved = mapAssetRepository.save(asset)
        return MapAssetDto.from(saved)
    }

    fun getTeamAssets(
        teamId: UUID,
        user: AuthenticatedUser,
        type: MapAssetType? = null,
        categoryId: UUID? = null,
        tag: String? = null
    ): MapAssetListResponse {
        if (!teamService.isTeamMember(teamId, user)) {
            throw IllegalAccessException("Not a member of this team")
        }

        val assets = when {
            type != null -> mapAssetRepository.findByTeamIdAndTypeAndDeletedFalse(teamId, type)
            categoryId != null -> mapAssetRepository.findByTeamIdAndCategoryIdAndDeletedFalse(teamId, categoryId)
            tag != null -> mapAssetRepository.findByTeamIdAndTagsContainingIgnoreCaseAndDeletedFalse(teamId, tag)
            else -> mapAssetRepository.findByTeamIdAndDeletedFalse(teamId)
        }

        val assetDtos = assets.map { MapAssetDto.from(it) }
        return MapAssetListResponse(assets = assetDtos, total = assetDtos.size)
    }

    fun getAsset(teamId: UUID, assetId: UUID, user: AuthenticatedUser): MapAssetDto? {
        if (!teamService.isTeamMember(teamId, user)) {
            return null
        }

        val asset = mapAssetRepository.findByIdAndTeamIdAndDeletedFalse(assetId, teamId)
            ?: return null

        return MapAssetDto.from(asset)
    }

    @Transactional
    fun updateAsset(
        teamId: UUID,
        assetId: UUID,
        request: UpdateMapAssetRequest,
        user: AuthenticatedUser
    ): MapAssetDto? {
        if (!teamService.isTeamMember(teamId, user)) {
            return null
        }

        val asset = mapAssetRepository.findByIdAndTeamIdAndDeletedFalse(assetId, teamId)
            ?: return null

        request.name?.let { asset.name = it }
        request.description?.let { asset.description = it }
        request.styleData?.let { asset.styleData = it }
        request.previewUrl?.let { asset.previewUrl = it }
        request.tags?.let { asset.tags = it }
        request.defaultGeometry?.let { asset.defaultGeometry = GeoJsonGeometry.toGeometry(it) }

        if (request.categoryId != null) {
            val category = assetCategoryRepository.findByIdAndTeamIdAndDeletedFalse(request.categoryId, teamId)
            asset.category = category
        }

        val saved = mapAssetRepository.save(asset)
        return MapAssetDto.from(saved)
    }

    @Transactional
    fun deleteAsset(teamId: UUID, assetId: UUID, user: AuthenticatedUser): Boolean {
        if (!teamService.isTeamAdmin(teamId, user)) {
            return false
        }

        val asset = mapAssetRepository.findByIdAndTeamIdAndDeletedFalse(assetId, teamId)
            ?: return false

        // Check if asset is in use
        val usages = overlayLayerRepository.findByMapAssetIdAndDeletedFalse(assetId)
        if (usages.isNotEmpty()) {
            throw IllegalStateException("Asset is in use by ${usages.size} segment(s)")
        }

        asset.softDelete()
        mapAssetRepository.save(asset)
        return true
    }

    @Transactional
    fun duplicateAsset(teamId: UUID, assetId: UUID, user: AuthenticatedUser): MapAssetDto? {
        if (!teamService.isTeamMember(teamId, user)) {
            return null
        }

        val asset = mapAssetRepository.findByIdAndTeamIdAndDeletedFalse(assetId, teamId)
            ?: return null

        val duplicate = MapAsset(
            team = asset.team,
            category = asset.category,
            name = "${asset.name} (Copy)",
            description = asset.description,
            type = asset.type,
            styleData = asset.styleData.toMap(),
            defaultGeometry = asset.defaultGeometry?.copy(),
            previewUrl = asset.previewUrl,
            tags = asset.tags
        )

        val saved = mapAssetRepository.save(duplicate)
        return MapAssetDto.from(saved)
    }
}
