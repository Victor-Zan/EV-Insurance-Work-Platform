import type { DraftForm } from './work-orders'
import {plateCheck} from './intake-values.ts'

export const intakeFields: {key:keyof DraftForm;label:string}[]=[
  {key:'claimNo',label:'案件号（保险报案号）'},{key:'ownerPhone',label:'车主手机号'},
  {key:'vehiclePlate',label:'车牌号/车架号'},
  {key:'surveyorName',label:'查勘员（姓名 电话）'},
  {key:'vehicleColor',label:'车辆颜色（选填）'},{key:'vehicleBrand',label:'车辆品牌（选填）'},{key:'vehicleModel',label:'车型（选填）'},
]
export function intakeMissing(form:DraftForm,photos:number){
  const required=[...intakeFields.filter(item=>['claimNo','ownerPhone'].includes(item.key)),{key:'surveyorName' as const,label:'查勘员姓名'},{key:'surveyorPhone' as const,label:'查勘员电话'}]
  const missing=required.filter(item=>!form[item.key]?.trim()).map(item=>item.label)
  if(!form.vehiclePlate.trim()&&!form.vehicleVin.trim())missing.push('车辆牌照或车架号')
  if(!form.vehicleVin.trim()&&form.vehiclePlate.trim()&&!plateCheck(form.vehiclePlate).acceptable)missing.push('有效车牌号或车架号')
  if(!photos)missing.push('至少1张现场物损照片')
  return missing
}
export function validateSceneFiles(files:File[]){
  if(files.length>20)throw Error('现场物损照片最多20张')
  for(const file of files){if(!/\.(jpe?g|png)$/i.test(file.name)||file.size<=0||file.size>10485760)throw Error('物损照片仅支持JPG/PNG，每张不超过10 MiB')}
}
export async function readChatFiles(files:File[]){
  if(files.some(file=>/\.zip$/i.test(file.name))){
    if(files.length!==1)throw Error('请一次选择一个ZIP包，或直接上传聊天记录文件夹')
    const {readChatZip}=await import('./intake-zip.ts')
    files=await readChatZip(files[0]!)
  }
  const texts=files.filter(file=>/\.txt$/i.test(file.name)),photos=files.filter(file=>/\.(jpe?g|png)$/i.test(file.name))
  if(!texts.length)throw Error('导入资料中没有UTF-8 TXT聊天记录')
  if(files.some(file=>!texts.includes(file)&&!photos.includes(file)))throw Error('文件夹仅支持TXT和JPG/PNG，请移除其他文件后重试')
  validateSceneFiles(photos)
  if(texts.reduce((sum,file)=>sum+file.size,0)>200000)throw Error('文字文件合计不超过200000字节')
  texts.sort((a,b)=>(a.webkitRelativePath||a.name).localeCompare(b.webkitRelativePath||b.name))
  const parts=[]
  for(const file of texts){const value=new TextDecoder('utf-8',{fatal:true}).decode(await file.arrayBuffer());parts.push(texts.length>1?`【文件：${file.webkitRelativePath||file.name}】\n${value}`:value)}
  const text=parts.join('\n\n')
  if(!text.trim()||text.includes('\0')||text.length>50000||text.split(/\r\n|\n|\r/).length-(/[\r\n]$/.test(text)?1:0)>1000)throw Error('文字须为1—50000字符且不超过1000行')
  return {text,photos}
}
