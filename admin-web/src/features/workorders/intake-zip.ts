import { BlobReader, ZipReader, ERR_UNSAFE_FILENAME } from '@zip.js/zip.js'

// Read each entry in bounded chunks; never extract archive paths to the filesystem.
export async function readChatZip(archive:File):Promise<File[]>{
  const reader=new ZipReader(new BlobReader(archive),{useWebWorkers:false,checkSignature:true,filenameEncoding:'gb18030'})
  const files:File[]=[],paths=new Set<string>()
  let textBytes=0,photoCount=0
  try{
    for await(const entry of reader.getEntriesGenerator()){
      const path=entry.filename.replaceAll('\\','/')
      if(path.startsWith('/')||/^[a-z]:/i.test(path)||path.includes('\0')||path.split('/').includes('..'))throw Error('ZIP包含异常文件路径，请重新打包')
      if(entry.directory)continue
      if(paths.has(path))throw Error('ZIP包含重复文件路径，请重新打包')
      paths.add(path)
      if(entry.encrypted)throw Error('不支持加密ZIP，请解密后重新上传')
      const isText=/\.txt$/i.test(path),isPhoto=/\.(jpe?g|png)$/i.test(path)
      if(!isText&&!isPhoto)throw Error('ZIP仅支持TXT和JPG/PNG，请移除其他文件后重试')
      if(isPhoto&&++photoCount>20)throw Error('现场物损照片最多20张')
      const limit=isText?200000-textBytes:10485760
      const sizeError=isText?'文字文件合计不超过200000字节':'物损照片仅支持JPG/PNG，每张不超过10 MiB'
      if(entry.uncompressedSize>limit||(isPhoto&&entry.uncompressedSize===0))throw Error(sizeError)
      const chunks:Uint8Array<ArrayBuffer>[]= []
      let size=0
      await entry.getData(new WritableStream<Uint8Array>({write(chunk){
        size+=chunk.byteLength
        if(size>limit)throw Error(sizeError)
        chunks.push(new Uint8Array(chunk))
      }}))
      if(size!==entry.uncompressedSize)throw Error('ZIP文件大小校验失败，请重新打包')
      if(isText)textBytes+=size
      const file=new File(chunks,path.split('/').at(-1)!,{type:isText?'text/plain':/\.png$/i.test(path)?'image/png':'image/jpeg'})
      Object.defineProperty(file,'webkitRelativePath',{value:path})
      files.push(file)
    }
    return files
  }catch(error){
    if(error instanceof Error&&error.message===ERR_UNSAFE_FILENAME)throw Error('ZIP包含异常文件路径，请重新打包',{cause:error})
    if(error instanceof Error&&/[\u4e00-\u9fff]/.test(error.message))throw error
    throw Error('ZIP无法读取或校验失败，请确认压缩包完整且未加密',{cause:error})
  }finally{await reader.close()}
}
