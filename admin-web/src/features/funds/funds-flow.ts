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
interface ConfirmationState {stagedFlow:boolean;flowStage:string;receiveConfirmed:boolean;canConfirmReceive:boolean;canConfirmPay:boolean;receivable?:{target:string|null;net:string;status:string};payable:{target:string|null;net:string;status:string}}
export function fundsConfirmationIssue(data:ConfirmationState|undefined):{message:string;field:'amount'|'number'|'refresh'}|undefined{
 const direction=fundsDirection(data)
 if(!data||!data.stagedFlow||!direction)return {message:'当前资金步骤已变化，请刷新案件后核对。',field:'refresh'}
 if(direction==='PAY'&&!data.receiveConfirmed)return {message:'请先完成保险公司到账确认，再办理网点结算。',field:'refresh'}
 const balance=direction==='PAY'?data.payable:data.receivable,pay=direction==='PAY'
 if(balance?.target==null)return {message:pay?'请填写结算金额并点击“保存应付金额”，再记录结算流水。':'请填写到账金额并点击“保存应收金额”，再记录到账流水。',field:'amount'}
 if(balance.status!=='SETTLED')return {message:`${pay?'结算':'到账'}流水尚未结清：已记录 ${balance.net} 元，${pay?'应付':'应收'} ${balance.target} 元。请填写本次金额和凭证编号，点击“保存${pay?'结算':'到账'}流水”。`,field:'number'}
 if(!(pay?data.canConfirmPay:data.canConfirmReceive))return {message:'当前收车、派单或资金确认依据已变化，请刷新并核对记录。',field:'refresh'}
}
