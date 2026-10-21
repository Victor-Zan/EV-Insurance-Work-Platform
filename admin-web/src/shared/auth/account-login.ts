import type { AxiosInstance } from 'axios'
import {portal,type AuthSession,type LoginResult} from './session.ts'
export async function unifiedLogin(client:AxiosInstance,session:AuthSession,username:string,password:string){
  const destination=(await client.post<{data:{portal:string;loginUrl:string;code:string}}>('/auth/sign-in',{username,password})).data.data
  if(destination.portal!==portal)return destination.loginUrl+'#handoff='+encodeURIComponent(destination.code)
  await redeem(client,session,destination.code);return null
}
async function redeem(client:AxiosInstance,session:AuthSession,code:string){const result=(await client.post<{data:LoginResult}>('/auth/handoff',{code,portal})).data.data;session.set(result)}
export async function consumeHandoff(client:AxiosInstance,session:AuthSession){const code=new URLSearchParams(location.hash.slice(1)).get('handoff');if(!code)return false;history.replaceState(null,'',location.pathname+location.search);session.clear();await redeem(client,session,code);return true}
