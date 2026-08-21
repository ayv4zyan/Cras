import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import {
  createWavHeader,
  encodePcmWav,
  downsampleTo16kHz,
  AudioRecorder,
} from "./audioRecorder";

describe("Audio Recorder & WAV Encoding", () => {
  it("generates a valid 44-byte WAV header with correct format and sample rate", () => {
    const dataByteLength = 32000; // 1 second of 16kHz 16-bit mono
    const header = createWavHeader(dataByteLength, 16000, 1, 16);

    expect(header.byteLength).toBe(44);
    const view = new DataView(header);

    // RIFF identifier
    const riff = String.fromCharCode(
      view.getUint8(0),
      view.getUint8(1),
      view.getUint8(2),
      view.getUint8(3),
    );
    expect(riff).toBe("RIFF");

    // Total file size - 8
    expect(view.getUint32(4, true)).toBe(36 + 32000);

    // WAVE identifier
    const wave = String.fromCharCode(
      view.getUint8(8),
      view.getUint8(9),
      view.getUint8(10),
      view.getUint8(11),
    );
    expect(wave).toBe("WAVE");

    // Audio format = 1 (PCM)
    expect(view.getUint16(20, true)).toBe(1);

    // Channels = 1 (mono)
    expect(view.getUint16(22, true)).toBe(1);

    // Sample rate = 16000
    expect(view.getUint32(24, true)).toBe(16000);

    // Byte rate = 16000 * 1 * 2 = 32000
    expect(view.getUint32(28, true)).toBe(32000);

    // Block align = 2
    expect(view.getUint16(32, true)).toBe(2);

    // Bits per sample = 16
    expect(view.getUint16(34, true)).toBe(16);

    // Subchunk2Size
    expect(view.getUint32(40, true)).toBe(32000);
  });

  it("encodes float PCM samples into a 16-bit signed integer WAV Blob", async () => {
    const samples = new Float32Array([0.0, 0.5, -0.5, 1.0, -1.0]);
    const blob = encodePcmWav(samples, 16000);

    expect(blob.type).toBe("audio/wav");
    expect(blob.size).toBe(44 + samples.length * 2);

    const arrayBuffer = await blob.arrayBuffer();
    const dataView = new DataView(arrayBuffer);

    // 16-bit PCM values
    expect(dataView.getInt16(44, true)).toBe(0);
    expect(dataView.getInt16(46, true)).toBe(Math.floor(0.5 * 32767));
    expect(dataView.getInt16(48, true)).toBe(Math.floor(-0.5 * 32768));
    expect(dataView.getInt16(50, true)).toBe(32767);
    expect(dataView.getInt16(52, true)).toBe(-32768);
  });

  it("downsamples 48 kHz buffer to 16 kHz mono correctly", () => {
    const sampleRate48k = 48000;
    const input48k = new Float32Array(48000); // 1 second
    for (let i = 0; i < input48k.length; i++) {
      input48k[i] = Math.sin((i / 48000) * 2 * Math.PI * 440);
    }

    const output16k = downsampleTo16kHz(input48k, sampleRate48k);
    expect(output16k.length).toBe(16000);
  });

  it("leaves 16 kHz buffer unchanged when downsample is called", () => {
    const input16k = new Float32Array(16000);
    input16k[0] = 0.42;
    const output = downsampleTo16kHz(input16k, 16000);
    expect(output).toBe(input16k);
  });
});

describe("AudioRecorder lifecycle", () => {
  let mockTrack: { stop: ReturnType<typeof vi.fn> };
  let mockStream: { getTracks: ReturnType<typeof vi.fn> };
  let mockAudioContext: {
    sampleRate: number;
    state: string;
    createMediaStreamSource: ReturnType<typeof vi.fn>;
    createScriptProcessor: ReturnType<typeof vi.fn>;
    destination: Record<string, unknown>;
    close: ReturnType<typeof vi.fn>;
  };
  let mockScriptProcessor: {
    onaudioprocess: ((e: AudioProcessingEvent) => void) | null;
    connect: ReturnType<typeof vi.fn>;
    disconnect: ReturnType<typeof vi.fn>;
  };
  let mockSource: {
    connect: ReturnType<typeof vi.fn>;
    disconnect: ReturnType<typeof vi.fn>;
  };

  beforeEach(() => {
    mockTrack = { stop: vi.fn() };
    mockStream = {
      getTracks: vi.fn().mockReturnValue([mockTrack]),
    };

    mockScriptProcessor = {
      onaudioprocess: null,
      connect: vi.fn(),
      disconnect: vi.fn(),
    };

    mockSource = {
      connect: vi.fn(),
      disconnect: vi.fn(),
    };

    mockAudioContext = {
      sampleRate: 48000,
      state: "running",
      createMediaStreamSource: vi.fn().mockReturnValue(mockSource),
      createScriptProcessor: vi.fn().mockReturnValue(mockScriptProcessor),
      destination: {},
      close: vi.fn().mockResolvedValue(undefined),
    };

    Object.defineProperty(globalThis.navigator, "mediaDevices", {
      value: {
        getUserMedia: vi.fn().mockResolvedValue(mockStream),
      },
      writable: true,
      configurable: true,
    });
    (globalThis as unknown as { AudioContext: typeof AudioContext }).AudioContext =
      vi.fn().mockImplementation(() => mockAudioContext) as unknown as typeof AudioContext;
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("starts recording and handles audio process chunks", async () => {
    const recorder = new AudioRecorder();
    expect(recorder.isRecording()).toBe(false);

    await recorder.start();
    expect(recorder.isRecording()).toBe(true);
    expect(recorder.getStartTime()).toBeInstanceOf(Date);

    // Simulate audio chunks arriving
    const fakeChunk = new Float32Array(4096);
    fakeChunk[0] = 0.25;
    mockScriptProcessor.onaudioprocess({
      inputBuffer: {
        getChannelData: () => fakeChunk,
      },
    });

    const result = await recorder.stop();
    expect(recorder.isRecording()).toBe(false);
    expect(result.blob.type).toBe("audio/wav");
    expect(result.durationSeconds).toBeGreaterThan(0);
    expect(mockTrack.stop).toHaveBeenCalled();
  });

  it("cancels recording and cleans up all tracks and context", async () => {
    const recorder = new AudioRecorder();
    await recorder.start();
    expect(recorder.isRecording()).toBe(true);

    recorder.cancel();
    expect(recorder.isRecording()).toBe(false);
    expect(mockTrack.stop).toHaveBeenCalled();
    expect(mockScriptProcessor.disconnect).toHaveBeenCalled();
    expect(mockAudioContext.close).toHaveBeenCalled();
  });

  it("throws when stopping without recording", async () => {
    const recorder = new AudioRecorder();
    await expect(recorder.stop()).rejects.toThrow("No active recording to stop.");
  });
});
