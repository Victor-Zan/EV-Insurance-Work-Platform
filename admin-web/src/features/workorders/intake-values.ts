import type {DraftForm} from './work-orders'
import {mergeOcrDraft} from '../materials/ocr-form.ts'

const provinces='京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵青藏琼宁川粤'
export function plateCheck(input:string){
 const value=input.normalize('NFKC').toUpperCase().replace(/[\s·•.\-－—]/g,'')
 const ordinary=new RegExp(`^[${provinces}][A-HJ-NP-Z][A-HJ-NP-Z0-9]{4}[A-HJ-NP-Z0-9学挂警港澳]$`)
 const energy=new RegExp(`^[${provinces}][A-HJ-NP-Z](?:[DF][A-HJ-NP-Z0-9][0-9]{4}|[0-9]{5}[DF])$`)
 if(ordinary.test(value)||energy.test(value))return {value,acceptable:true,review:false,message:'符合常见大陆机动车号牌结构'}
 const special=value.length>=4&&value.length<=20&&/^[\p{Script=Han}A-Z0-9]+$/u.test(value)&&/\d/.test(value)&&(/^[0-9]{6,12}$/.test(value)||/^[\p{Script=Han}]{1,6}[A-Z0-9]{4,12}$/u.test(value)||/^[A-Z]{1,3}[0-9]{4,10}$/.test(value))
 return {value,acceptable:special,review:true,message:special?'地方电动自行车或特殊号牌，请按原件人工核对':'号牌格式不完整，请核对原件或填写车架号'}
}
export function identityPatch(input:string,kind:'AUTO'|'PLATE'|'VIN'='AUTO'){
 const value=input.normalize('NFKC').trim().toUpperCase()
 const frame=kind==='VIN'||(kind==='AUTO'&&(/^[A-Z0-9]{17}$/.test(value)||/^[0-9]{15}$/.test(value)||/^VIN[-:]/.test(value)))
 return {vehiclePlate:frame?'':plateCheck(value).value,vehicleVin:frame?value:''}
}
export function surveyorPatch(input:string){
 const phone=input.match(/(?:1[3-9][0-9]{9}|0[0-9]{2,3}[- ]?[0-9]{7,8})/)
 return {surveyorName:(phone?input.replace(phone[0],''):input).replace(/^[+＋/\s]+|[+＋/\s]+$/g,'').trim(),surveyorPhone:phone?.[0].replace(/ /g,'')??''}
}
export function surveyorText(form:DraftForm){return [form.surveyorName,form.surveyorPhone].filter(Boolean).join(' ')}
export function mergeIntakeDraft(current:DraftForm,fields:Record<string,unknown>){
 const result=mergeOcrDraft(current,fields)
 result.conflicts=result.conflicts.filter(item=>item.field!=='surveyorName')
 for(const [field,other] of [['vehiclePlate','vehicleVin'],['vehicleVin','vehiclePlate']] as const){
  if(!current[field]?.trim()&&current[other]?.trim()&&result.incoming[field]){result.merged[field]=current[field];result.conflicts.push({field,incoming:result.incoming[field]})}
 }
 return result
}
