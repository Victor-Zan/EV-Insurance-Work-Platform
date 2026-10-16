export interface ReviewLine {description:string;quantity:string;unitPrice:string}
const maximum=10n**38n-1n
export function moneyCents(value:string){
 if(!/^[0-9]{1,36}(\.[0-9]{1,2})?$/.test(value))throw Error('单价须为非负金额，最多2位小数')
 const [whole,fraction='']=value.split('.');return BigInt(whole)*100n+BigInt(fraction.padEnd(2,'0'))
}
export function yuan(cents:bigint){return (cents/100n).toString()+'.'+(cents%100n).toString().padStart(2,'0')}
export function reviewCalculation(lines:ReviewLine[],rate:string){
 if(!lines.length||lines.length>1000)throw Error('报价须包含1—1000项')
 const amounts=lines.map((line,index)=>{
  if(!line.description.trim()||line.description.length>255)throw Error(`第${index+1}项请填写项目名称，最多255字`)
  if(!/^[1-9][0-9]{0,9}$/.test(line.quantity)||BigInt(line.quantity)>2147483647n)throw Error(`第${index+1}项数量须为有效正整数`)
  return moneyCents(line.unitPrice)*BigInt(line.quantity)
 })
 const base=amounts.reduce((sum,amount)=>sum+amount,0n),percentage=moneyCents(rate)
 if(percentage>10000n)throw Error('加价率须在0—100%之间')
 const markup=(base*percentage+5000n)/10000n,total=base+markup
 if(total>maximum)throw Error('金额超出存储范围')
 return {amounts:amounts.map(yuan),base:yuan(base),markup:yuan(markup),total:yuan(total)}
}
export function reviewPayload(lines:ReviewLine[]){
 reviewCalculation(lines,'0')
 return lines.map(line=>({description:line.description.trim(),quantity:Number(line.quantity),unitPrice:yuan(moneyCents(line.unitPrice))}))
}
export function reviewLines(lines:unknown[]):ReviewLine[]{return lines.map(line=>{const row=line as Record<string,unknown>;return {description:String(row.description??''),quantity:String(row.quantity??''),unitPrice:String(row.unitPrice??'')}})}
