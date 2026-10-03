package com.evinsurance.platform.organization.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.evinsurance.platform.organization.api.OrganizationRequests;
import com.evinsurance.platform.organization.infrastructure.*;
import com.evinsurance.platform.identity.domain.CurrentUser;
import com.evinsurance.platform.audit.application.AuditService;
import com.evinsurance.platform.audit.application.AuditService.Action;
import com.evinsurance.platform.foundation.api.*;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrganizationService {
    private final RegionMapper regions; private final ShopMapper shops;
    private final OrganizationRelationsMapper relations; private final AuditService audit;
    public OrganizationService(RegionMapper regions,ShopMapper shops,OrganizationRelationsMapper relations,AuditService audit) {
        this.regions=regions; this.shops=shops; this.relations=relations; this.audit=audit;
    }
    public PageResponse<RegionEntity> regions(int page,int size,Long parentId) {
        CurrentUser.require().requireAdmin(); int offset=PageResponse.offset(page,size);
        var query=new QueryWrapper<RegionEntity>(); if (parentId!=null) query.eq("parent_id",parentId);
        long total=regions.selectCount(query);
        return new PageResponse<>(page,size,total,regions.selectList(query.orderByAsc("sort_order","id").last("LIMIT " + size + " OFFSET " + offset)));
    }
    @Transactional
    public RegionEntity saveRegion(Long id,OrganizationRequests.Region request) {
        var actor=CurrentUser.require(); actor.requireAdmin(); RegionEntity before=null;
        if (id!=null) { before=relations.lockRegion(id); if (before==null) throw ApiException.missing("Region"); }
        if (request.level()==1 && request.parentId()!=null || request.level()>1 && request.parentId()==null) throw ApiException.invalid("Province has no parent; city and district require a parent");
        if (Objects.equals(id,request.parentId()) && id!=null) throw ApiException.invalid("Region cannot be its own parent");
        if (request.parentId()!=null) {
            var parent=regions.selectById(request.parentId()); if (parent==null) throw ApiException.missing("Parent region");
            if (parent.getLevel()!=request.level()-1) throw ApiException.invalid("Parent must be the immediately preceding level");
        }
        if (before!=null && !before.getLevel().equals(request.level()) && relations.childCount(id)>0) throw ApiException.invalid("Cannot change the level of a region with children");
        var region=new RegionEntity(); region.setId(id); region.setParentId(request.parentId()); region.setLevel(request.level());
        region.setName(request.name()); region.setCode(request.code()); region.setEnabled(request.enabled()); region.setSortOrder(request.sortOrder());
        region.setUpdatedAt(Instant.now()); region.setUpdatedBy(actor.id());
        if (id==null) { region.setCreatedAt(region.getUpdatedAt()); region.setCreatedBy(actor.id()); regions.insert(region); }
        else regions.update(null,new UpdateWrapper<RegionEntity>().eq("id",id).set("parent_id",request.parentId()).set("level",request.level())
            .set("name",request.name()).set("code",request.code()).set("enabled",request.enabled()).set("sort_order",request.sortOrder())
            .set("updated_at",region.getUpdatedAt()).set("updated_by",actor.id()));
        audit.record(actor,id==null ? Action.REGION_CREATE : Action.REGION_UPDATE,"REGION",region.getId(),
            "Fields: name,code; parent: " + (before==null ? null : before.getParentId()) + " -> " + request.parentId()
            + "; level: " + request.level() + "; enabled: " + (before==null ? null : before.getEnabled()) + " -> " + request.enabled()
            + "; sort: " + (before==null ? null : before.getSortOrder()) + " -> " + request.sortOrder());
        return regions.selectById(region.getId());
    }
    public PageResponse<ShopEntity> shops(int page,int size) {
        CurrentUser.require().requireAdmin(); int offset=PageResponse.offset(page,size);
        return new PageResponse<>(page,size,shops.selectCount(null),shops.selectList(new QueryWrapper<ShopEntity>().orderByAsc("id").last("LIMIT " + size + " OFFSET " + offset)));
    }
    @Transactional
    public ShopEntity saveShop(Long id,OrganizationRequests.Shop request) {
        var actor=CurrentUser.require(); actor.requireAdmin(); ShopEntity before=null;
        if (id!=null) { before=relations.lockShop(id); if (before==null) throw ApiException.missing("Shop"); }
        var shop=new ShopEntity(); shop.setId(id); shop.setName(request.name()); shop.setCode(request.code()); shop.setEnabled(request.enabled());
        shop.setContactName(request.contactName()); shop.setContactPhone(request.contactPhone()); shop.setAddress(request.address());
        shop.setUpdatedAt(Instant.now()); shop.setUpdatedBy(actor.id());
        if (id==null) { shop.setCreatedAt(shop.getUpdatedAt()); shop.setCreatedBy(actor.id()); shops.insert(shop); }
        else shops.update(null,new UpdateWrapper<ShopEntity>().eq("id",id).set("name",request.name()).set("code",request.code()).set("enabled",request.enabled())
            .set("contact_name",request.contactName()).set("contact_phone",request.contactPhone()).set("address",request.address())
            .set("updated_at",shop.getUpdatedAt()).set("updated_by",actor.id()));
        audit.record(actor,id==null ? Action.SHOP_CREATE : Action.SHOP_UPDATE,"SHOP",shop.getId(),
            "Fields: name,code,contact,address; enabled: " + (before==null ? null : before.getEnabled()) + " -> " + request.enabled());
        return shops.selectById(shop.getId());
    }
    public PageResponse<Long> serviceRegions(long shopId,int page,int size) {
        CurrentUser.require().requireAdmin(); requireShop(shopId); int offset=PageResponse.offset(page,size);
        return new PageResponse<>(page,size,relations.serviceRegionCount(shopId),relations.serviceRegions(shopId,offset,size));
    }
    @Transactional
    public void setServiceRegions(long shopId,Set<Long> ids) {
        var actor=CurrentUser.require(); actor.requireAdmin();
        if (relations.lockShop(shopId)==null) throw ApiException.missing("Shop");
        for (long id: ids) if (regions.selectById(id)==null) throw ApiException.missing("Region");
        var before=relations.serviceRegions(shopId,0,100); relations.deleteServiceRegions(shopId);
        ids.stream().sorted().forEach(id -> relations.addServiceRegion(shopId,id,actor.id()));
        audit.record(actor,Action.SERVICE_REGION_CHANGE,"SHOP",shopId,"Region IDs: " + before + " -> " + ids.stream().sorted().toList());
    }
    private void requireShop(long id) { if (shops.selectById(id)==null) throw ApiException.missing("Shop"); }
}
