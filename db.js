// Audio stays in this extension's IndexedDB. Each chunk and its metadata
// are committed together, so an interrupted browser can retain prior chunks.
let database;

function openDatabase() {
  if (!database) {
    database = new Promise((resolve, reject) => {
      const request = indexedDB.open('stream-saver', 1);
      request.onupgradeneeded = () => {
        const db = request.result;
        db.createObjectStore('recordings', { keyPath: 'id' });
        db.createObjectStore('chunks', { keyPath: ['recordingId', 'index'] });
      };
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => { database = null; reject(request.error); };
      request.onblocked = () => reject(new Error('Close other Stream Saver pages and try again.'));
    });
  }
  return database;
}

async function transaction(stores, mode, action) {
  const db = await openDatabase();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(stores, mode);
    let result;
    tx.oncomplete = () => resolve(result);
    tx.onabort = () => reject(tx.error || new Error('Local storage transaction failed.'));
    tx.onerror = () => {}; // onabort reports errors once the transaction rolls back.
    try {
      action(tx, value => { result = value; });
    } catch (error) {
      tx.abort();
      reject(error);
    }
  });
}

function chunkRange(id) {
  return IDBKeyRange.bound([id, 0], [id, Number.MAX_SAFE_INTEGER]);
}

export function createRecording(recording) {
  return transaction(['recordings'], 'readwrite', tx => {
    tx.objectStore('recordings').add(recording);
  });
}

export function getRecording(id) {
  return transaction(['recordings'], 'readonly', (tx, result) => {
    tx.objectStore('recordings').get(id).onsuccess = event => result(event.target.result);
  });
}

export function listRecordings() {
  return transaction(['recordings'], 'readonly', (tx, result) => {
    tx.objectStore('recordings').getAll().onsuccess = event => {
      result(event.target.result.sort((a, b) => b.startedAt - a.startedAt));
    };
  });
}

export function appendChunk(id, index, blob, durationMs) {
  return transaction(['recordings', 'chunks'], 'readwrite', (tx, result) => {
    const store = tx.objectStore('recordings');
    store.get(id).onsuccess = event => {
      const recording = event.target.result;
      if (!recording) { tx.abort(); return; }
      tx.objectStore('chunks').add({ recordingId: id, index, blob });
      recording.bytes += blob.size;
      recording.chunkCount += 1;
      recording.durationMs = durationMs;
      store.put(recording);
      result(recording);
    };
  });
}

export function updateRecording(id, fields) {
  return transaction(['recordings'], 'readwrite', (tx, result) => {
    const store = tx.objectStore('recordings');
    store.get(id).onsuccess = event => {
      const recording = event.target.result;
      if (!recording) { tx.abort(); return; }
      Object.assign(recording, fields);
      store.put(recording);
      result(recording);
    };
  });
}

export function deleteRecording(id) {
  return transaction(['recordings', 'chunks'], 'readwrite', tx => {
    tx.objectStore('recordings').delete(id);
    tx.objectStore('chunks').delete(chunkRange(id));
  });
}

export async function getRecordingBlob(id) {
  const data = await transaction(['recordings', 'chunks'], 'readonly', (tx, result) => {
    let recording;
    const chunks = [];
    tx.objectStore('recordings').get(id).onsuccess = event => { recording = event.target.result; };
    tx.objectStore('chunks').openCursor(chunkRange(id)).onsuccess = event => {
      const cursor = event.target.result;
      if (cursor) {
        chunks.push(cursor.value.blob);
        cursor.continue();
      } else {
        result({ get recording() { return recording; }, chunks });
      }
    };
  });
  if (!data.recording) throw new Error('This recording no longer exists.');
  if (data.recording.status === 'recording') throw new Error('Stop recording before downloading.');
  if (!data.chunks.length) throw new Error('No audio was saved for this recording.');
  return { recording: data.recording, blob: new Blob(data.chunks, { type: data.recording.mimeType }) };
}

export function recoverRecordings() {
  return transaction(['recordings'], 'readwrite', tx => {
    tx.objectStore('recordings').openCursor().onsuccess = event => {
      const cursor = event.target.result;
      if (!cursor) return;
      const recording = cursor.value;
      if (recording.status === 'recording') {
        recording.status = 'interrupted';
        recording.finishedAt = recording.startedAt + recording.durationMs;
        recording.note = 'The browser or extension stopped unexpectedly. Saved audio may be incomplete.';
        cursor.update(recording);
      }
      cursor.continue();
    };
  });
}
