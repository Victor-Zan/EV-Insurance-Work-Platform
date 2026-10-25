export function chinaBusinessTime(now=new Date()){
 const parts=new Intl.DateTimeFormat('en-GB',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hourCycle:'h23'}).formatToParts(now)
 const part=(type:string)=>parts.find(value=>value.type===type)?.value??''
 return `${part('year')}-${part('month')}-${part('day')}T${part('hour')}:${part('minute')}`
}
export function fundsDirection(data:{stagedFlow:boolean;flowStage:string;receivable?:{status:string}}|undefined):'RECEIVE'|'PAY'|undefined{
 if(!data||['CANCELLED','CLOSED'].includes(data.flowStage))return
 if(data.stagedFlow)return data.flowStage==='WAITING_INSURER_PAYMENT'?'RECEIVE':data.flowStage==='WAITING_SHOP_SETTLEMENT'?'PAY':undefined
 return data.receivable?.status==='SETTLED'?'PAY':'RECEIVE'
}
export function fundsAmount(input:string,positive=false){
 const value=input.trim()
 if(!/^[0-9]{1,36}(\.[0-9]{1,2})?$/.test(value))throw Error('请输入有效金额，最多保留两位小数')
 const [whole,fraction='']=value.split('.'),integer=BigInt(whole!)
 if(positive&&integer===0n&&BigInt(fraction||'0')===0n)throw Error('本次实际金额必须大于零')
 return `${integer}.${fraction.padEnd(2,'0')}`
}
interface ConfirmationState {stagedFlow:boolean;flowStage:string;receiveConfirmed:boolean;canConfirmReceive:boolean;canConfirmPay:boolean;receivable?:{target:string|null;net:string;status:string};payable:{target:string|null;net:string;status:string}}
export function fundsConfirmationIssue(data:ConfirmationState|undefined):{message:string;field:'amount'|'number'|'refresh'}|undefined{
 const direction=fundsDirection(data)
 if(!data||!data.stagedFlow||!direction)return {message:'当前资金步骤已变化，请刷新案件后核对。',field:'refresh'}
 if(direction==='PAY'&&!data.receiveConfirmed)return {message:'请先完成保险公司到账确认，再办理网点结算。',field:'refresh'}
 const balance=direction==='PAY'?data.payable:data.receivable,pay=direction==='PAY'
 if(balance?.target==null)return {message:`请先填写并确认${pay?'应付':'应收'}金额。`,field:'amount'}
 if(balance.status!=='SETTLED')return {message:`${pay?'结算':'到账'}流水尚未结清：已记录 ${balance.net} 元，${pay?'应付':'应收'} ${balance.target} 元。`,field:'number'}
 if(!(pay?data.canConfirmPay:data.canConfirmReceive))return {message:'当前收车、派单或资金确认依据已变化，请刷新并核对记录。',field:'refresh'}
}
