import { build } from 'esbuild';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
const root = path.dirname(fileURLToPath(import.meta.url));
await build({entryPoints:[path.join(root,'src/app.js')],bundle:true,minify:true,format:'esm',target:'es2022',
  outfile:path.join(root,'../target/classes/static/admin/app.js'),legalComments:'eof'});
console.log('Administration portal built.');
