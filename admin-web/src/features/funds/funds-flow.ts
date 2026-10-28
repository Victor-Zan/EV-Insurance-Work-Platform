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
export function fundsFailureMessage(error:unknown){
 const messages:Record<string,string>={FUNDS_VERSION_CONFLICT:'资金记录已有更新，已尝试刷新；请核对金额后再次确认。',TRANSACTION_CONFLICT:'该流水号 / 凭证编号已用于其他记录，请核对编号和金额。',IDEMPOTENCY_CONFLICT:'本次请求与先前提交不一致，请刷新并核对资金记录后重试。',FUNDS_TARGET_CONFLICT:'应收或应付金额与已记录流水不一致，请先核对或冲销历史流水。'}
 const code=error&&typeof error==='object'&&'code' in error?String(error.code):''
 return messages[code]??(error instanceof Error?error.message:'操作失败')
}
interface ConfirmationState {stagedFlow:boolean;flowStage:string;receiveConfirmed:boolean;canConfirmReceive:boolean;canConfirmPay:boolean;receivable?:{target:string|null;net:string;status:string};payable:{target:string|null;net:string;status:string}}
export function fundsConfirmationIssue(data:ConfirmationState|undefined):{message:string;field:'amount'|'number'|'refresh'}|undefined{
 const direction=fundsDirection(data)
 if(!data||!data.stagedFlow||!direction)return {message:'当前资金步骤已变化，请刷新案件后核对。',field:'refresh'}
 if(direction==='PAY'&&!data.receiveConfirmed)return {message:'请先完成保险公司到账确认，再办理网点结算。',field:'refresh'}
 const balance=direction==='PAY'?data.payable:data.receivable,pay=direction==='PAY'
 if(balance?.target==null)return {message:`${pay?'应付':'应收'}金额尚未保存，请刷新并核对最终确认金额。`,field:'amount'}
 if(balance.status!=='SETTLED')return {message:`${pay?'结算':'到账'}流水尚未结清：已记录 ${balance.net} 元，${pay?'应付':'应收'} ${balance.target} 元。`,field:'number'}
 if(!(pay?data.canConfirmPay:data.canConfirmReceive))return {message:'当前收车、派单或资金确认依据已变化，请刷新并核对记录。',field:'refresh'}
}
