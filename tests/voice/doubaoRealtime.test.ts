import test from 'node:test'
import assert from 'node:assert/strict'
import { EventEmitter } from 'node:events'

import {
  DoubaoRealtimeAsrClient,
  buildDoubaoDuplexHeaders,
  buildDoubaoSessionEvent
} from '../../src/voice/providers/doubaoRealtime.js'

class FakeDuplexSocket extends EventEmitter {
  sent = []
  closed = false

  send(payload) {
    const event = JSON.parse(Buffer.isBuffer(payload) ? payload.toString('utf8') : String(payload))
    this.sent.push(event)

    if (event.type === 'session.create') {
      queueMicrotask(() => this.emitJson({
        type: 'session.created',
        session: { id: 'session-1', model: '1.2.6.1' }
      }))
      return
    }

    if (event.type === 'input_audio_buffer.commit') {
      queueMicrotask(() => {
        this.emitJson({
          type: 'conversation.item.input_audio_transcription.completed',
          transcript: 'ni hao',
          item_id: 'item-1'
        })
      })
    }
  }

  emitJson(event) {
    this.emit('message', Buffer.from(JSON.stringify(event), 'utf8'))
  }

  close() {
    this.closed = true
  }
}

test('buildDoubaoDuplexHeaders uses only the new-console X-Api-Key header', () => {
  assert.deepEqual(buildDoubaoDuplexHeaders({ apiKey: 'api-key-123' }), {
    'X-Api-Key': 'api-key-123'
  })
})

test('buildDoubaoSessionEvent builds session.create JSON with the official model and audio formats', () => {
  const event = buildDoubaoSessionEvent({
    instructions: 'You are a Chinese Minecraft NPC.',
    inputFormat: { type: 'pcm', rate: 16000 },
    outputFormat: { type: 'pcm_s16le', rate: 24000 }
  })

  assert.deepEqual(event, {
    type: 'session.create',
    session: {
      model: '1.2.6.1',
      instructions: 'You are a Chinese Minecraft NPC.',
      audio: {
        input: { format: { type: 'pcm', rate: 16000 } },
        output: { format: { type: 'pcm_s16le', rate: 24000 } }
      }
    }
  })
})

test('buildDoubaoSessionEvent supports session.update without changing the session shape', () => {
  const event = buildDoubaoSessionEvent({
    type: 'session.update',
    model: '1.2.6.1',
    instructions: 'Speak briefly.',
    inputFormat: { type: 'pcm', rate: 16000 },
    outputFormat: { type: 'ogg_opus', rate: 24000 }
  })

  assert.equal(event.type, 'session.update')
  assert.equal(event.session.model, '1.2.6.1')
  assert.equal(event.session.instructions, 'Speak briefly.')
  assert.deepEqual(event.session.audio.output.format, { type: 'ogg_opus', rate: 24000 })
})


test('DoubaoRealtimeAsrClient appends base64 PCM and parses completed transcription events', async () => {
  const sockets = []
  const client = new DoubaoRealtimeAsrClient({
    apiKey: 'api-key-123',
    chunkSize: 4,
    chunkIntervalMs: 0,
    wsFactory: (url, options) => {
      const socket = new FakeDuplexSocket()
      sockets.push({ url, options, socket })
      queueMicrotask(() => socket.emit('open'))
      return socket
    }
  })

  const audio = Buffer.from([1, 2, 3, 4, 5, 6])
  const result = await client.recognizePcm(audio)
  const sent = sockets[0].socket.sent

  assert.equal(sent[0].type, 'session.create')
  assert.deepEqual(sent.slice(1, 3), [
    {
      type: 'input_audio_buffer.append',
      audio: Buffer.from([1, 2, 3, 4]).toString('base64')
    },
    {
      type: 'input_audio_buffer.append',
      audio: Buffer.from([5, 6]).toString('base64')
    }
  ])
  assert.deepEqual(sent[3], { type: 'input_audio_buffer.commit' })
  assert.equal(result.text, 'ni hao')
  assert.equal(result.source, 'doubao-realtime-asr')
  assert.equal(sockets[0].socket.closed, true)
})

test('duplex realtime ASR fails before opening a socket when DOUBAO_API_KEY is missing', async () => {
  let opened = false
  const previousKey = process.env.DOUBAO_API_KEY
  delete process.env.DOUBAO_API_KEY
  const adapter = new DoubaoRealtimeAsrClient({
    apiKey: '',
    wsFactory: () => {
      opened = true
      return new FakeDuplexSocket()
    }
  })

  try {
    await assert.rejects(
      adapter.recognizePcm(Buffer.from([1, 2])),
      /DOUBAO_API_KEY/
    )
    assert.equal(opened, false)
  } finally {
    if (previousKey === undefined) delete process.env.DOUBAO_API_KEY
    else process.env.DOUBAO_API_KEY = previousKey
  }
})
