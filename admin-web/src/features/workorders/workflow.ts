export interface WorkflowMaterial {category:string;required:boolean;present:boolean;satisfied:boolean;exceptionAccepted:boolean;canUpload:boolean}
export interface WorkflowNode {key:string;label:string;state:string;completedAt?:string}
export interface Workflow {caseId:string;status:string;currentNode:string;actorRole:string;action:string;canAct:boolean;canContinue:boolean;missing:string[];nodes:WorkflowNode[];materials:WorkflowMaterial[];legacyNeedsReview:boolean}
export function currentTaskSection(node:string){return node==='CREATE'?'overview':node==='DISPATCH_ARRIVAL'?'dispatch':['SHOP_QUOTE','QUOTE_REVIEW','INSURER_ASSESSMENT','REPAIR_AUTHORIZATION'].includes(node)?'quotation':['REPAIR','RECEIPT','COMPLETE'].includes(node)?'repair':'overview'}
export function safeCaseSection(requested:string,node:string){if(node==='CREATE'&&['funds','complaint'].includes(requested))return 'overview';return ['funds','complaint','history','overview'].includes(requested)?requested:currentTaskSection(node)}
export const materialNames:Record<string,string>={SCENE_PHOTO:'现场物损照片',NOTICE:'历史通知单',SHOP_QUOTE:'报价原件',ASSESSMENT:'定损资料',LOSS_ASSESSMENT:'核损单',ARRIVAL_PHOTO:'到店照',PROGRESS_PHOTO:'进度照',COMPLETION_PHOTO:'完工照'}

export const nodeNames:Record<string,string>={CREATE:'建单',DISPATCH_ARRIVAL:'派单到店',SHOP_QUOTE:'网点报价',QUOTE_REVIEW:'报价审核',INSURER_ASSESSMENT:'保险核损',REPAIR_AUTHORIZATION:'维修授权',REPAIR:'维修',RECEIPT:'收车',COMPLETE:'完成'}
