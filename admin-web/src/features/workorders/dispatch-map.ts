import type {MapLocation,MapShop} from './work-orders'
export function mapMarkers(origin:MapLocation,shops:MapShop[]){
 const points=[origin.point,...shops.map(shop=>shop.mockLocation.point)]
 const lat=points.map(point=>point.latitude),lon=points.map(point=>point.longitude)
 const minLat=Math.min(...lat),maxLat=Math.max(...lat),minLon=Math.min(...lon),maxLon=Math.max(...lon)
 return points.map(point=>({x:48+(point.longitude-minLon)/Math.max(maxLon-minLon,.002)*504,y:272-(point.latitude-minLat)/Math.max(maxLat-minLat,.002)*220}))
}
export function recommendedShop(shops:MapShop[]){return shops.find(shop=>typeof shop.distanceMetres==='number'&&Number.isFinite(shop.distanceMetres))?.id}
export function locationAddress(city:string,address:string){const text=address.trim();return text.includes(city)?text:city+text}
