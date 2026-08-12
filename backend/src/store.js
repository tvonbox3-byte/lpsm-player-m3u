import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';
import { randomUUID } from 'node:crypto';

const seed = { clients: [], playlists: [], appearance: { bannerUrl: '', wallpaperUrl: '', supportMessage: 'Use apenas conteúdo autorizado.' }, audit: [] };

export class Store {
  constructor(file) { this.file = file; this.data = structuredClone(seed); this.queue = Promise.resolve(); }
  async load() {
    try { this.data = { ...structuredClone(seed), ...JSON.parse(await readFile(this.file, 'utf8')) }; }
    catch (e) { if (e.code !== 'ENOENT') throw e; await this.save(); }
  }
  async save() {
    await mkdir(dirname(this.file), { recursive: true });
    const tmp = `${this.file}.tmp`;
    await writeFile(tmp, JSON.stringify(this.data, null, 2));
    await rename(tmp, this.file);
  }
  mutate(fn) { this.queue = this.queue.then(async () => { const value = fn(this.data); await this.save(); return value; }); return this.queue; }
  addAudit(action, detail = '') { this.data.audit.unshift({ id: randomUUID(), at: new Date().toISOString(), action, detail }); this.data.audit = this.data.audit.slice(0, 200); }
}

export const id = () => randomUUID();
