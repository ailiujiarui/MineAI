import test from 'node:test'
import assert from 'node:assert/strict'
import { EventEmitter } from 'node:events'

import {
  DoubaoRealtimeAsrClient,
  DoubaoRealtimeTtsAdapter,
  buildDoubaoRealtimeConnectHeaders,
  decodeDoubaoRealtimeFrame
} from '../../src/voice/providers/doubaoRealtime.js'

class FakeRealtimeSocket extends EventEmitter {
  constructor() {
    super()
    this.sent = []
    this.closed = false
  }

  sent
  closed

  send(payload) {
    const buffer = Buffer.from(payload)
    const frame = decodeDoubaoRealtimeFrame(buffer)
    this.sent.push(frame)

    if (frame.event === 1) {
      queueMicrotask(() => {
        this.emit('message', encodeJsonEvent(50, {}))
      })
      return
    }

    if (frame.event === 100) {
      queueMicrotask(() => {
        this.emit('message', encodeJsonEvent(150, { dialog_id: 'dlg-1' }, { sessionId: frame.sessionId }))
      })
      return
    }

    if (frame.event === 300) {
      queueMicrotask(() => {
        this.emit('message', encodeAudioFrame(Buffer.from('voice-a'), frame.sessionId))
        this.emit('message', encodeAudioFrame(Buffer.from('voice-b'), frame.sessionId))
        this.emit('message', encodeJsonEvent(359, { question_id: 'q1', reply_id: 'r1' }, { sessionId: frame.sessionId }))
      })
      return
    }

    if (frame.event === 400) {
      queueMicrotask(() => {
        this.emit('message', encodeJsonEvent(451, {
          results: [{ text: 'ni hao', is_interim: false }]
        }, { sessionId: frame.sessionId }))
        this.emit('message', encodeJsonEvent(459, {}, { sessionId: frame.sessionId }))
      })
    }
  }

  close() {
    this.closed = true
  }
}

function writeInt32(value) {
  const buffer = Buffer.alloc(4)
  buffer.writeInt32BE(value, 0)
  return buffer
}

function encodeHeader(messageType, flags, serialization = 1, compression = 0) {
  return Buffer.from([
    0x11,
    ((messageType & 0x0f) << 4) | (flags & 0x0f),
    ((serialization & 0x0f) << 4) | (compression & 0x0f),
    0x00
  ])
}

function encodeJsonEvent(eventId, payload = {}, options = {}) {
  const payloadBuffer = Buffer.from(JSON.stringify(payload), 'utf8')
  const parts = [encodeHeader(0x9, 0x4, 1, 0), writeInt32(eventId)]

  if (options.connectId) {
    const connectBuffer = Buffer.from(options.connectId, 'utf8')
    parts.push(writeInt32(connectBuffer.length), connectBuffer)
  }

  if (options.sessionId) {
    const sessionBuffer = Buffer.from(options.sessionId, 'utf8')
    parts.push(writeInt32(sessionBuffer.length), sessionBuffer)
  }

  parts.push(writeInt32(payloadBuffer.length), payloadBuffer)
  return Buffer.concat(parts)
}

function encodeAudioFrame(audio, sessionId) {
  const sessionBuffer = Buffer.from(sessionId, 'utf8')
  return Buffer.concat([
    encodeHeader(0xb, 0x4, 0, 0),
    writeInt32(352),
    writeInt32(sessionBuffer.length),
    sessionBuffer,
    writeInt32(audio.length),
    audio
  ])
}

test('buildDoubaoRealtimeConnectHeaders builds the official v3 realtime dialogue headers', () => {
  const headers = buildDoubaoRealtimeConnectHeaders({
    appId: '123456789',
    accessToken: 'access-key',
    resourceId: 'volc.speech.dialog',
    appKey: 'PlgvMymc7f3tQnJ6',
    connectId: 'cid-1'
  })

  assert.deepEqual(headers, {
    'X-Api-App-ID': '123456789',
    'X-Api-Access-Key': 'access-key',
    'X-Api-Resource-Id': 'volc.speech.dialog',
    'X-Api-App-Key': 'PlgvMymc7f3tQnJ6',
    'X-Api-Connect-Id': 'cid-1'
  })
})

test('decodeDoubaoRealtimeFrame decodes StartSession-style JSON frames', () => {
  const sessionId = '75a6126e-427f-49a1-a2c1-621143cb9db3'
  const frame = encodeJsonEvent(100, {
    dialog: {
      extra: { input_mod: 'text', model: '2.2.0.0' }
    }
  }, { sessionId })

  const decoded = decodeDoubaoRealtimeFrame(frame)
  assert.equal(decoded.event, 100)
  assert.equal(decoded.sessionId, sessionId)
  assert.equal(decoded.payload.dialog.extra.model, '2.2.0.0')
})

test('DoubaoRealtimeTtsAdapter follows StartConnection -> StartSession -> SayHello flow', async () => {
  const sockets = []
  const adapter = new DoubaoRealtimeTtsAdapter({
    appId: '123456789',
    accessToken: 'access-key',
    speakerId: 'S_demo',
    realtime: {
      resourceId: 'volc.speech.dialog',
      appKey: 'PlgvMymc7f3tQnJ6',
      model: '2.2.0.0'
    },
    wsFactory: (url, options) => {
      const socket = new FakeRealtimeSocket()
      sockets.push({ url, options, socket })
      queueMicrotask(() => socket.emit('open'))
      return socket
    }
  })

  const result = await adapter.synthesize({
    text: 'hello realtime'
  })

  assert.equal(sockets.length, 1)
  assert.equal(sockets[0].url, 'wss://openspeech.bytedance.com/api/v3/realtime/dialogue')
  assert.equal(sockets[0].options.headers['X-Api-App-ID'], '123456789')
  assert.equal(sockets[0].options.headers['X-Api-Access-Key'], 'access-key')
  assert.equal(sockets[0].options.headers['X-Api-Resource-Id'], 'volc.speech.dialog')

  assert.equal(sockets[0].socket.sent[0].event, 1)
  assert.equal(sockets[0].socket.sent[1].event, 100)
  assert.equal(sockets[0].socket.sent[1].payload.tts.speaker, 'S_demo')
  assert.equal(sockets[0].socket.sent[1].payload.dialog.extra.input_mod, 'text')
  assert.equal(sockets[0].socket.sent[1].payload.dialog.extra.model, '2.2.0.0')
  assert.equal(sockets[0].socket.sent[2].event, 300)
  assert.equal(sockets[0].socket.sent[2].payload.content, 'hello realtime')
  assert.equal(sockets[0].socket.sent[3].event, 102)
  assert.equal(result.provider, 'doubao-realtime')
  assert.equal(result.mimeType, 'audio/wav')
  assert.ok(result.audio.length > 44)
  assert.equal(sockets[0].socket.closed, true)
})

test('DoubaoRealtimeAsrClient streams audio via TaskRequest and completes on ASREnded', async () => {
  const sockets = []
  const client = new DoubaoRealtimeAsrClient({
    appId: '123456789',
    accessToken: 'access-key',
    chunkSize: 4,
    chunkIntervalMs: 0,
    realtime: {
      resourceId: 'volc.speech.dialog',
      appKey: 'PlgvMymc7f3tQnJ6',
      model: '2.2.0.0'
    },
    wsFactory: (url, options) => {
      const socket = new FakeRealtimeSocket()
      sockets.push({ url, options, socket })
      queueMicrotask(() => socket.emit('open'))
      return socket
    }
  })

  const result = await client.recognizePcm(Buffer.from([1, 2, 3, 4, 5, 6]))

  assert.equal(sockets[0].socket.sent[0].event, 1)
  assert.equal(sockets[0].socket.sent[1].event, 100)
  assert.equal(sockets[0].socket.sent[1].payload.dialog.extra.input_mod, 'audio_file')
  assert.equal(sockets[0].socket.sent[2].messageType, 0x2)
  assert.equal(sockets[0].socket.sent[2].payload.toString('hex'), Buffer.from([1, 2, 3, 4]).toString('hex'))
  assert.equal(sockets[0].socket.sent[3].messageType, 0x2)
  assert.equal(sockets[0].socket.sent[4].event, 400)
  assert.equal(result.text, 'ni hao')
  assert.equal(result.source, 'doubao-realtime-asr')
  assert.equal(sockets[0].socket.closed, true)
})
