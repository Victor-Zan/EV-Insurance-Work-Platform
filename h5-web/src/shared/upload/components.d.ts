import FileDropZone from './FileDropZone.vue'
declare module 'vue' {
  export interface GlobalComponents { FileDropZone: typeof FileDropZone }
}
export {}
