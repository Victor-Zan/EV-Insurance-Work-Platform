import type { DraftForm } from './work-orders'

export const intakeFields: {key:keyof DraftForm;label:string}[]=[
  {key:'claimNo',label:'案件号（保险报案号）'},{key:'ownerPhone',label:'车主手机号'},
  {key:'vehiclePlate',label:'车辆牌照'},{key:'vehicleVin',label:'车架号 / VIN'},
  {key:'surveyorName',label:'查勘员姓名'},{key:'surveyorPhone',label:'查勘员电话'},
  {key:'vehicleColor',label:'车辆颜色'},{key:'vehicleBrand',label:'车辆品牌'},{key:'vehicleModel',label:'车型'},
]
export function intakeMissing(form:DraftForm,photos:number){
  const missing=intakeFields.filter(item=>!['vehiclePlate','vehicleVin'].includes(item.key)&&!form[item.key]?.trim()).map(item=>item.label)
  if(!form.vehiclePlate.trim()&&!form.vehicleVin.trim())missing.push('车辆牌照或车架号')
  if(!photos)missing.push('至少1张现场物损照片')
  return missing
}
export function validateSceneFiles(files:File[]){
  if(files.length>20)throw Error('现场物损照片最多20张')
  for(const file of files){if(!/\.(jpe?g|png)$/i.test(file.name)||file.size<=0||file.size>10485760)throw Error('物损照片仅支持JPG/PNG，每张不超过10 MiB')}
}
export async function readChatFiles(files:File[]){
  const texts=files.filter(file=>/\.txt$/i.test(file.name)),photos=files.filter(file=>/\.(jpe?g|png)$/i.test(file.name))
  if(!texts.length)throw Error('文件夹中没有UTF-8 TXT聊天记录')
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
