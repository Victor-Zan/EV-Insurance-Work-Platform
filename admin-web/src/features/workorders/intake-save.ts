import type {DraftForm,WorkOrder} from './work-orders'

interface SaveApi {
 detail(id:string):Promise<WorkOrder>;update(id:string,form:DraftForm):Promise<WorkOrder>
 submit(id:string,confirmed:boolean,reason:string|null,key:string):Promise<WorkOrder>
}
export async function saveIntake(options:{api:SaveApi;pending?:WorkOrder;form:DraftForm;formal:boolean;submitKey:string;
 create(form:DraftForm):Promise<WorkOrder>;upload(id:string):Promise<void>;saved(order:WorkOrder):void;
 stage(value:'DRAFT'|'PHOTOS'|'SUBMIT'):void;confirmDuplicate():Promise<string>}){
 const {api}=options
 options.stage('DRAFT')
 let order=options.pending?await api.detail(options.pending.id):undefined
 if(order&&order.status!=='DRAFT'){options.saved(order);return order}
 order=order?await api.update(order.id,options.form):await options.create(options.form)
 options.saved(order);options.stage('PHOTOS');await options.upload(order.id)
 if(!options.formal)return order
 options.stage('SUBMIT')
 order=await submitReviewedDraft(api,order.id,options.submitKey,options.confirmDuplicate)
 options.saved(order);return order
}
export async function submitReviewedDraft(api:Pick<SaveApi,'submit'|'detail'>,id:string,key:string,confirmDuplicate:()=>Promise<string>){
 try{
  try{return await api.submit(id,false,null,key)}catch(error){
   if(!error||typeof error!=='object'||!('code' in error)||error.code!=='POSSIBLE_DUPLICATE')throw error
   return await api.submit(id,true,await confirmDuplicate(),key)
  }
 }catch(error){
  // A failed response is not proof of a failed commit. Read before allowing another write.
  let current:WorkOrder|undefined
  try{current=await api.detail(id)}catch{/* Retain the original failure and the known draft ID. */}
  if(!current||current.status==='DRAFT')throw error
  return current
 }
}
export async function openSavedCase(router:{push(path:string):Promise<unknown>;resolve(path:string):{href:string}},id:string,reload:(href:string)=>void){
 const path=`/work-orders/${id}`
 try{await router.push(path)}catch{reload(router.resolve(path).href)}
}
