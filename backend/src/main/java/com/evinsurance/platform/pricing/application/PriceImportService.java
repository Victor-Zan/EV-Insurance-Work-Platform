package com.evinsurance.platform.pricing.application;
import com.evinsurance.platform.pricing.domain.*;
import com.evinsurance.platform.pricing.domain.ImportData.*;
import com.evinsurance.platform.pricing.domain.ImportData.Error;
import com.evinsurance.platform.pricing.infrastructure.*;
import com.evinsurance.platform.foundation.api.*;
import com.evinsurance.platform.foundation.web.TraceContext;
import com.evinsurance.platform.audit.application.AuditService;
import com.evinsurance.platform.audit.application.AuditService.Action;
import tools.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.util.*;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataAccessException;

@Service public class PriceImportService {
 private final PriceFileParser parser;private final ImportValidator validator;private final PriceMapper prices;private final PriceService pricing;
 private final ImportPreviewMapper previews;private final ImportBatchMapper batches;private final ImportErrorMapper errors;private final ImportMapper mapper;
 private final AuditService audit;private final ObjectMapper json;private final TransactionTemplate tx;
 public PriceImportService(PriceFileParser parser,ImportValidator validator,PriceMapper prices,PriceService pricing,
  ImportPreviewMapper previews,ImportBatchMapper batches,ImportErrorMapper errors,ImportMapper mapper,AuditService audit,ObjectMapper json,PlatformTransactionManager manager){
  this.parser=parser;this.validator=validator;this.prices=prices;this.pricing=pricing;this.previews=previews;this.batches=batches;this.errors=errors;this.mapper=mapper;this.audit=audit;this.json=json;this.tx=new TransactionTemplate(manager);
 }
 @Transactional public Preview preview(MultipartFile file){
  var actor=PricingAccess.write();var parsed=parser.parse(file);var result=validator.validate(parsed.rows(),false);
  var p=new ImportPreviewEntity();p.setId(UUID.randomUUID());p.setActorId(actor.id());p.setFileName(parsed.fileName());p.setFileFormat(parsed.format());p.setSha256(parsed.sha256());p.setCreatedAt(Instant.now());p.setExpiresAt(p.getCreatedAt().plusSeconds(1800));p.setTotalRows(parsed.rows().size());previews.insert(p);
  var stored=new ArrayList<ImportMapper.Row>();
  for(int i=0;i<result.size();i++)stored.add(new ImportMapper.Row(result.get(i).rowNumber(),pricing.encode(parsed.rows().get(i)),pricing.encode(result.get(i))));
  for(int offset=0;offset<stored.size();offset+=500)mapper.rows(p.getId(),stored.subList(offset,Math.min(offset+500,stored.size())));
  int invalid=(int)result.stream().filter(r->!r.errors().isEmpty()).count();
  int duplicate=(int)result.stream().filter(r->r.errors().stream().anyMatch(e->e.reason().startsWith("Duplicate"))).count();
  audit.recordIdentifier(actor,Action.PRICE_IMPORT_PREVIEW,"PRICE_IMPORT_PREVIEW",p.getId().toString(),
   "Rows "+result.size()+"; invalid "+invalid+"; source "+p.getFileFormat());
  return new Preview(p.getId(),p.getFileName(),p.getFileFormat(),result.size(),result.size()-invalid,invalid,duplicate,p.getExpiresAt());
 }
 private ImportPreviewEntity requirePreview(UUID id,boolean locked){
  var actor=PricingAccess.write();var p=locked?mapper.lock(id):previews.selectById(id);
  if(p==null)throw ApiException.missing("Import preview");if(!Objects.equals(p.getActorId(),actor.id()))throw ApiException.denied();return p;
 }
 public PageResponse<Result> previewRows(UUID id,int page,int size){
  var p=requirePreview(id,false);int offset=PageResponse.offset(page,size);
  return new PageResponse<>(page,size,p.getTotalRows(),mapper.stored(id,offset,size).stream().map(r->decode(r.getResult(),Result.class)).toList());
 }
 public Preview summary(UUID id){
  var p=requirePreview(id,false);int invalid=0,duplicate=0;
  for(int offset=0;offset<p.getTotalRows();offset+=500)for(var row:mapper.stored(id,offset,500)){
   var result=decode(row.getResult(),Result.class);if(!result.errors().isEmpty())invalid++;
   if(result.errors().stream().anyMatch(e->e.reason().startsWith("Duplicate")))duplicate++;
  }
  return new Preview(id,p.getFileName(),p.getFileFormat(),p.getTotalRows(),p.getTotalRows()-invalid,invalid,duplicate,p.getExpiresAt());
 }
 public ImportBatchEntity confirm(UUID id){
  PricingAccess.write();
  try{return tx.execute(status->confirmLocked(id));}
  catch(DataAccessException conflict){
   // The complete price transaction has rolled back before persisting a failed batch.
   return tx.execute(status->{
    var p=requirePreview(id,true);var existing=mapper.byPreview(id);if(existing!=null)return existing;
    List<RawRow> rows=raw(p);var failed=rows.stream().map(row->new Result(row.rowNumber(),row.values(),
     List.of(new Error(row.rowNumber(),"database","Concurrent database conflict; all price writes rolled back","")),"INVALID",null)).toList();
    return finish(p,failed,false);
   });
  }
 }
 private ImportBatchEntity confirmLocked(UUID id){
  var p=requirePreview(id,true);var existing=mapper.byPreview(id);if(existing!=null)return existing;
  if(!p.getExpiresAt().isAfter(Instant.now()))throw new ApiException(HttpStatus.GONE,"IMPORT_PREVIEW_EXPIRED","Preview expired; upload and validate again");
  var result=validator.validate(raw(p),true);boolean valid=result.stream().allMatch(r->r.errors().isEmpty());
  var batch=finish(p,result,valid);
  if(valid){
   var actor=PricingAccess.write();var drafts=result.stream().map(Result::draft).toList();String rows=pricing.encode(drafts);
   prices.ensureRecords(rows,actor.id());String roles=actor.roles().stream().map(Enum::name).sorted().collect(java.util.stream.Collectors.joining(","));
   String trace=TraceContext.currentTraceId();if(trace==null)trace=UUID.randomUUID().toString();
   var inserted=prices.apply(rows,actor.id(),roles,trace,batch.getId());
   if(inserted.size()!=drafts.size())throw new org.springframework.dao.DataIntegrityViolationException("Import count mismatch");
  }
  return batch;
 }
 private List<RawRow> raw(ImportPreviewEntity p){
  var rows=new ArrayList<RawRow>();for(int offset=0;offset<p.getTotalRows();offset+=500)
   for(var row:mapper.stored(p.getId(),offset,500))rows.add(decode(row.getRawValues(),RawRow.class));
  return rows;
 }
 private ImportBatchEntity finish(ImportPreviewEntity p,List<Result> result,boolean valid){
  var actor=PricingAccess.write();var b=new ImportBatchEntity();b.setPreviewId(p.getId());b.setFileName(p.getFileName());b.setSource(p.getFileFormat());b.setSha256(p.getSha256());b.setStatus(valid?"SUCCESS":"FAILED");
  b.setTotalRows(result.size());b.setSuccessCount(valid?result.size():0);b.setFailureCount(valid?0:result.size());b.setActorId(actor.id());b.setCreatedAt(Instant.now());b.setCompletedAt(b.getCreatedAt());batches.insert(b);
  if(!valid){
   var report=new ArrayList<ImportMapper.ErrorRow>();
   for(var row:result){
    if(row.errors().isEmpty())report.add(new ImportMapper.ErrorRow(row.rowNumber(),"batch","Batch rejected because other rows are invalid; no price writes",""));
    else for(var e:row.errors())report.add(new ImportMapper.ErrorRow(e.rowNumber(),e.field(),e.reason(),e.originalValue()));
   }
   for(int offset=0;offset<report.size();offset+=500)mapper.errors(b.getId(),report.subList(offset,Math.min(offset+500,report.size())));
  }
  mapper.consume(p.getId());audit.record(actor,valid?Action.PRICE_IMPORT_SUCCESS:Action.PRICE_IMPORT_FAILURE,"PRICE_IMPORT_BATCH",b.getId(),
   "Rows "+result.size()+"; success "+b.getSuccessCount()+"; failure "+b.getFailureCount()+"; source "+b.getSource());
  return batches.selectById(b.getId());
 }
 private <T> T decode(String text,Class<T> type){try{return json.readValue(text,type);}catch(tools.jackson.core.JacksonException e){throw new IllegalStateException("Stored import data invalid");}}
 public PageResponse<ImportBatchEntity> batches(int page,int size){
  PricingAccess.write();int offset=PageResponse.offset(page,size);return new PageResponse<>(page,size,batches.selectCount(null),
   batches.selectList(new QueryWrapper<ImportBatchEntity>().orderByDesc("id").last("LIMIT "+size+" OFFSET "+offset)));
 }
 public ImportBatchEntity batch(long id){PricingAccess.write();var b=batches.selectById(id);if(b==null)throw ApiException.missing("Import batch");return b;}
 public PageResponse<ImportErrorEntity> errors(long id,int page,int size){
  batch(id);int offset=PageResponse.offset(page,size);var q=new QueryWrapper<ImportErrorEntity>().eq("batch_id",id);
  long count=errors.selectCount(q);return new PageResponse<>(page,size,count,errors.selectList(q.orderByAsc("row_number","id").last("LIMIT "+size+" OFFSET "+offset)));
 }
}
