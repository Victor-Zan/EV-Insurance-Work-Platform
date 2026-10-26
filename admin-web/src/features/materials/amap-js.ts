export interface MapPoint {longitude:number;latitude:number}
export interface JsEvent {lnglat?:{getLng():number;getLat():number}}
export interface JsMap {on(name:string,fn:(event:JsEvent)=>void):void;add(marker:JsMarker):void;remove(marker:JsMarker|JsMarker[]):void;setCenter(point:number[]):void;setZoom?(zoom:number):void;setFitView?(markers:JsMarker[],immediately?:boolean,padding?:number[],maxZoom?:number):void;destroy():void}
export interface JsMarker {on(name:string,fn:(event:JsEvent)=>void):void;setPosition(point:number[]):void;setDraggable(value:boolean):void;getPosition():{getLng():number;getLat():number};setMap(map:JsMap|null):void}
export interface AmapNamespace {Map:new(element:HTMLElement,options:Record<string,unknown>)=>JsMap;Marker:new(options:Record<string,unknown>)=>JsMarker}
export interface JsBootstrap {publicKey:string;servicePath:string;expires:string}
export interface BrowserMapWindow {AMap?:AmapNamespace;_AMapSecurityConfig?:{serviceHost:string};__evAmapReady?:()=>void}
let loading:Promise<AmapNamespace>|undefined,identity=''
/** Explicit invocation only. No side effects at module import; script is never cached locally. */
export function loadAmapJs(config:JsBootstrap,browser:BrowserMapWindow=window as BrowserMapWindow,doc:Document=document,origin=window.location.origin):Promise<AmapNamespace>{
 if(!/^[A-Za-z0-9_-]{16,128}$/.test(config.publicKey)||config.servicePath!=='/api/v1/maps/js-proxy')return Promise.reject(new Error('地图加载配置无效'))
 const requested=origin+config.servicePath+'|'+config.publicKey
 if(loading)return identity===requested?loading:Promise.reject(new Error('本页地图配置已改变，请重新打开页面'))
 if(browser.AMap||browser._AMapSecurityConfig)return Promise.reject(new Error('本页已有不同地图配置，请重新打开页面'))
 identity=requested
 loading=new Promise((resolve,reject)=>{
  const script=doc.createElement('script');let settled=false
  const finish=(api?:AmapNamespace)=>{if(settled)return;settled=true;clearTimeout(timer);delete browser.__evAmapReady;script.onerror=null;if(api)resolve(api);else{script.remove();reject(new Error('高德地图加载失败或超时；未自动重试，地址输入保留，请核对配置后重新打开页面'))}}
  const timer=setTimeout(()=>finish(),15000)
  browser._AMapSecurityConfig={serviceHost:origin+config.servicePath}
  browser.__evAmapReady=()=>finish(browser.AMap)
  script.src='https://webapi.amap.com/maps?v=2.0&key='+encodeURIComponent(config.publicKey)+'&callback=__evAmapReady'
  script.async=true;script.referrerPolicy='no-referrer';script.onerror=()=>finish();doc.head.appendChild(script)
 })
 return loading
}
export function pointFromEvent(event:JsEvent):MapPoint|undefined {const longitude=event.lnglat?.getLng(),latitude=event.lnglat?.getLat();return typeof longitude==='number'&&Number.isFinite(longitude)&&longitude>=-180&&longitude<=180&&typeof latitude==='number'&&Number.isFinite(latitude)&&latitude>=-90&&latitude<=90?{longitude,latitude}:undefined}
