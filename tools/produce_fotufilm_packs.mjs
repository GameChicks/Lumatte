// Offline model input production, no images, tests or Android build. The WASI builder only
// receives a pinned public stock plus settings and a read-only spectral-resource directory.
import { WASI } from 'node:wasi';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const source = resolve(root, 'research/fotufilm/model-reference-e15b098');
const cache = resolve(root, 'tools/.cache/fotufilm-profile-builder');
const index = JSON.parse(readFileSync(resolve(source, 'source-index.json')));
const sha = b => createHash('sha256').update(b).digest('hex');
for (const name of ['builder.wasm', 'rec2020-reflectance-prior.coeff']) {
  if (sha(readFileSync(resolve(cache, name))) !== index.publicAssets[name]) throw Error(`Changed ${name}`);
}
// The module receives only its spectral coefficient file, not access to the checkout.
const resources = resolve(cache, 'resources'); mkdirSync(resources, {recursive:true});
writeFileSync(resolve(resources, 'rec2020-reflectance-prior.coeff'), readFileSync(resolve(cache, 'rec2020-reflectance-prior.coeff')));
const wasi = new WASI({version:'preview1', args:['fotufilm-web-profile'],
  env:{FOTUFILM_RESOURCES:'/resources'}, preopens:{'/resources':resources}});
const module = await WebAssembly.compile(readFileSync(resolve(cache, 'builder.wasm')));
const instance = await WebAssembly.instantiate(module, {wasi_snapshot_preview1:wasi.wasiImport});
wasi.initialize(instance);
const api = instance.exports;
const packs = resolve(cache, 'packs'); mkdirSync(packs, {recursive:true});
const production = {};
for (const [id, upstream] of Object.entries(index.stockMapping)) {
  const profile = readFileSync(resolve(source, 'profiles', `${upstream}.json`));
  if (sha(profile) !== index.files[`profiles/${upstream}.json`]) throw Error(`Changed ${id}`);
  // Disable stochastic film grain during pointwise production; retain the existing Android
  // grain separately. No scene metering, filters, push/pull or new artistic grade is requested.
  const stock = JSON.parse(profile);
  const medium = stock.isReversal || ['acros100','trix400','hp5plus400','delta3200'].includes(upstream)
    ? 'screen' : stock.nativePrintMedium || (['c200','pro400h','superia400'].includes(upstream)
      ? 'crystal-archive' : ['ektar100','portra400'].includes(upstream) ? 'endura-premier' : 'ektacolor-edge');
  const request = {kind:'film', stock, width:1536, height:1024, medium,
    format:'35mm', controls:{grain:0, digitalReference:'reference-exposure'}};
  const input = new TextEncoder().encode(JSON.stringify(request));
  const pointer = api.profile_input(input.length);
  if (!pointer) throw Error('Profile input rejected');
  new Uint8Array(api.memory.buffer, pointer, input.length).set(input);
  const status = api.profile_prepare();
  const output = new Uint8Array(api.memory.buffer, api.profile_output(), api.profile_output_size()).slice();
  if (status) throw Error(new TextDecoder().decode(output));
  writeFileSync(resolve(packs, `${upstream}.pack`), output);
  production[id] = {upstream, sha256:sha(output), request:{...request, stock:undefined}};
  console.log(`Produced ${id}: ${output.length} bytes`);
}
writeFileSync(resolve(cache, 'production.json'), JSON.stringify(production, null, 2)+'\n');
