import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';
import { randomUUID } from 'node:crypto';

const seed = {
  clients: [],
  playlists: [],
  appearance: {
    bannerUrl: '',
    wallpaperUrl: '',
    supportMessage: 'Use apenas conteúdo autorizado.',
    adultPin: '0202'
  },
  audit: []
};

export class Store {
  constructor(file) {
    this.file = file;
    this.data = structuredClone(seed);
    this.queue = Promise.resolve();

    this.supabaseUrl = String(
      process.env.SUPABASE_URL || ''
    ).replace(/\/+$/, '');

    this.supabaseKey = String(
      process.env.SUPABASE_SECRET_KEY || ''
    );
  }

  get useSupabase() {
    return Boolean(
      this.supabaseUrl &&
      this.supabaseKey
    );
  }

  async supabaseRequest(
    path,
    options = {}
  ) {
    const response = await fetch(
      `${this.supabaseUrl}${path}`,
      {
        ...options,
        headers: {
          apikey: this.supabaseKey,
          'content-type': 'application/json',
          ...(options.headers || {})
        }
      }
    );

    if (!response.ok) {
      const text =
        await response.text();

      throw new Error(
        `Supabase ${response.status}: ${text}`
      );
    }

    if (
      response.status === 204
    ) {
      return null;
    }

    const text =
      await response.text();

    return text
      ? JSON.parse(text)
      : null;
  }

  async load() {
    if (!this.useSupabase) {
      return this.loadLocal();
    }

    try {
      const rows =
        await this.supabaseRequest(
          '/rest/v1/lpsm_state?id=eq.main&select=data',
          {
            method: 'GET'
          }
        );

      if (
        Array.isArray(rows) &&
        rows.length > 0 &&
        rows[0]?.data
      ) {
        this.data = {
          ...structuredClone(seed),
          ...rows[0].data
        };

        console.log(
          'LPSM: dados carregados do Supabase'
        );

        return;
      }

      this.data =
        structuredClone(seed);

      await this.createSupabaseState();

    } catch (error) {
      console.error(
        'Falha ao carregar Supabase:',
        error.message
      );

      throw error;
    }
  }

  async createSupabaseState() {
    await this.supabaseRequest(
      '/rest/v1/lpsm_state',
      {
        method: 'POST',

        headers: {
          Prefer:
            'resolution=merge-duplicates,return=minimal'
        },

        body: JSON.stringify({
          id: 'main',
          data: this.data,
          updated_at:
            new Date().toISOString()
        })
      }
    );
  }

  async save() {
    if (!this.useSupabase) {
      return this.saveLocal();
    }

    await this.supabaseRequest(
      '/rest/v1/lpsm_state?id=eq.main',
      {
        method: 'PATCH',

        headers: {
          Prefer: 'return=minimal'
        },

        body: JSON.stringify({
          data: this.data,
          updated_at:
            new Date().toISOString()
        })
      }
    );

    console.log(
      'LPSM: dados salvos no Supabase'
    );
  }

  async loadLocal() {
    try {
      this.data = {
        ...structuredClone(seed),
        ...JSON.parse(
          await readFile(
            this.file,
            'utf8'
          )
        )
      };

    } catch (error) {

      if (
        error.code !== 'ENOENT'
      ) {
        throw error;
      }

      await this.saveLocal();
    }
  }

  async saveLocal() {
    await mkdir(
      dirname(this.file),
      {
        recursive: true
      }
    );

    const tmp =
      `${this.file}.tmp`;

    await writeFile(
      tmp,
      JSON.stringify(
        this.data,
        null,
        2
      )
    );

    await rename(
      tmp,
      this.file
    );
  }

  mutate(fn) {
    this.queue =
      this.queue.then(
        async () => {

          const value =
            fn(this.data);

          await this.save();

          return value;
        }
      );

    return this.queue;
  }

  addAudit(
    action,
    detail = ''
  ) {
    this.data.audit.unshift({
      id: randomUUID(),
      at: new Date().toISOString(),
      action,
      detail
    });

    this.data.audit =
      this.data.audit.slice(
        0,
        200
      );
  }
}

export const id =
  () => randomUUID();
