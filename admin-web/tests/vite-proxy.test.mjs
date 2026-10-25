import test from 'node:test'
import assert from 'node:assert/strict'
import {fileURLToPath} from 'node:url'
import {loadConfigFromFile} from 'vite'

test('Vite proxy supports same-origin API paths and explicit backend URLs',async()=>{
  const previous=process.env.VITE_API_BASE_URL
  try {
    for(const [base,target] of [
      ['/api/v1','http://localhost:8080'],
      ['','http://localhost:8080'],
      ['http://localhost:8080/api/v1','http://localhost:8080'],
      ['http://127.0.0.1:18105/api/v1','http://127.0.0.1:18105'],
    ]){
      process.env.VITE_API_BASE_URL=base
      const loaded=await loadConfigFromFile({command:'serve',mode:'development'},fileURLToPath(new URL('../vite.config.ts',import.meta.url)))
      assert.ok(loaded)
      assert.equal(loaded.config.server.proxy['/api'].target,target)
      assert.equal(loaded.config.server.proxy['/api'].changeOrigin,false)
    }
  } finally {
    if(previous===undefined) delete process.env.VITE_API_BASE_URL
    else process.env.VITE_API_BASE_URL=previous
  }
})
