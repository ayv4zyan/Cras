/**
 * Audio Recorder Service for Cras Voice Capture
 * Produces mono 16 kHz 16-bit PCM WAV bounded by 2 minutes and 4 MB limits.
 */

export const TARGET_SAMPLE_RATE = 16000;
export const MAX_RECORDING_DURATION_MS = 120 * 1000; // 120 seconds = 2 minutes

export interface AudioRecordingResult {
  readonly blob: Blob;
  readonly durationSeconds: number;
  readonly sizeBytes: number;
}

/**
 * Creates a standard 44-byte WAV header for linear PCM data.
 */
export function createWavHeader(
  dataByteLength: number,
  sampleRate = TARGET_SAMPLE_RATE,
  numChannels = 1,
  bitsPerSample = 16,
): ArrayBuffer {
  const buffer = new ArrayBuffer(44);
  const view = new DataView(buffer);

  // "RIFF" chunk descriptor
  view.setUint8(0, 0x52); // R
  view.setUint8(1, 0x49); // I
  view.setUint8(2, 0x46); // F
  view.setUint8(3, 0x46); // F
  view.setUint32(4, 36 + dataByteLength, true); // Total file size - 8

  // "WAVE" format
  view.setUint8(8, 0x57); // W
  view.setUint8(9, 0x41); // A
  view.setUint8(10, 0x56); // V
  view.setUint8(11, 0x45); // E

  // "fmt " sub-chunk
  view.setUint8(12, 0x66); // f
  view.setUint8(13, 0x6d); // m
  view.setUint8(14, 0x74); // t
  view.setUint8(15, 0x20); // (space)
  view.setUint32(16, 16, true); // Subchunk1Size (16 for PCM)
  view.setUint16(20, 1, true); // AudioFormat (1 = PCM)
  view.setUint16(22, numChannels, true); // NumChannels
  view.setUint32(24, sampleRate, true); // SampleRate
  const byteRate = sampleRate * numChannels * (bitsPerSample / 8);
  view.setUint32(28, byteRate, true); // ByteRate
  const blockAlign = numChannels * (bitsPerSample / 8);
  view.setUint16(32, blockAlign, true); // BlockAlign
  view.setUint16(34, bitsPerSample, true); // BitsPerSample

  // "data" sub-chunk
  view.setUint8(36, 0x64); // d
  view.setUint8(37, 0x61); // a
  view.setUint8(38, 0x74); // t
  view.setUint8(39, 0x61); // a
  view.setUint32(40, dataByteLength, true); // Subchunk2Size

  return buffer;
}

/**
 * Encodes an array of float audio samples (-1.0 to 1.0) into a 16-bit PCM WAV Blob.
 */
export function encodePcmWav(
  samples: Float32Array,
  sampleRate = TARGET_SAMPLE_RATE,
): Blob {
  const dataByteLength = samples.length * 2;
  const header = createWavHeader(dataByteLength, sampleRate, 1, 16);
  const pcmData = new Int16Array(samples.length);

  for (let i = 0; i < samples.length; i++) {
    // Clamp sample between -1 and 1
    const s = Math.max(-1, Math.min(1, samples[i]));
    // Convert to 16-bit signed integer (-32768 to 32767)
    pcmData[i] = s < 0 ? s * 0x8000 : s * 0x7fff;
  }

  return new Blob([header, pcmData.buffer], { type: "audio/wav" });
}

/**
 * Resamples audio buffer to 16 kHz mono.
 */
export function downsampleTo16kHz(
  inputBuffer: Float32Array,
  inputSampleRate: number,
): Float32Array {
  if (inputSampleRate === TARGET_SAMPLE_RATE) {
    return inputBuffer;
  }

  const ratio = inputSampleRate / TARGET_SAMPLE_RATE;
  const newLength = Math.round(inputBuffer.length / ratio);
  const result = new Float32Array(newLength);

  for (let i = 0; i < newLength; i++) {
    const originalIndex = i * ratio;
    const indexLow = Math.floor(originalIndex);
    const indexHigh = Math.min(indexLow + 1, inputBuffer.length - 1);
    const fraction = originalIndex - indexLow;

    // Linear interpolation
    result[i] =
      inputBuffer[indexLow] * (1 - fraction) +
      inputBuffer[indexHigh] * fraction;
  }

  return result;
}

export class AudioRecorder {
  private mediaStream: MediaStream | null = null;
  private audioContext: AudioContext | null = null;
  private processor: ScriptProcessorNode | null = null;
  private sourceNode: MediaStreamAudioSourceNode | null = null;
  private recordedChunks: Float32Array[] = [];
  private recordingStartTime: number | null = null;
  private autoStopTimeout: number | null = null;
  private isRecordingActive = false;
  private onAutoStopCallback?: () => void;

  public isRecording(): boolean {
    return this.isRecordingActive;
  }

  public getStartTime(): Date | null {
    return this.recordingStartTime ? new Date(this.recordingStartTime) : null;
  }

  /**
   * Starts recording from the microphone.
   */
  public async start(options?: {
    readonly onAutoStop?: () => void;
  }): Promise<void> {
    if (this.isRecordingActive) {
      return;
    }

    if (
      typeof navigator === "undefined" ||
      !navigator.mediaDevices?.getUserMedia
    ) {
      throw new Error("Microphone recording is not supported in this browser.");
    }

    this.onAutoStopCallback = options?.onAutoStop;
    this.recordedChunks = [];

    try {
      this.mediaStream = await navigator.mediaDevices.getUserMedia({
        audio: {
          channelCount: 1,
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
        },
      });

      const AudioContextClass =
        window.AudioContext ||
        (window as unknown as { webkitAudioContext?: typeof AudioContext })
          .webkitAudioContext;
      if (!AudioContextClass) {
        throw new Error("AudioContext is not supported in this browser.");
      }

      this.audioContext = new AudioContextClass();
      this.sourceNode = this.audioContext.createMediaStreamSource(
        this.mediaStream,
      );

      // 4096 buffer size, 1 input channel, 1 output channel
      this.processor = this.audioContext.createScriptProcessor(4096, 1, 1);

      this.processor.onaudioprocess = (e) => {
        if (!this.isRecordingActive) return;
        const inputData = e.inputBuffer.getChannelData(0);
        // Copy data chunk
        this.recordedChunks.push(new Float32Array(inputData));
      };

      this.sourceNode.connect(this.processor);
      this.processor.connect(this.audioContext.destination);

      this.isRecordingActive = true;
      this.recordingStartTime = Date.now();

      // Automatically stop when reaching max duration (2 minutes)
      this.autoStopTimeout = window.setTimeout(() => {
        if (this.isRecordingActive) {
          this.onAutoStopCallback?.();
        }
      }, MAX_RECORDING_DURATION_MS);
    } catch (err) {
      this.cleanup();
      throw err;
    }
  }

  /**
   * Stops recording and returns the normalized 16 kHz PCM WAV result.
   */
  public async stop(): Promise<AudioRecordingResult> {
    if (!this.isRecordingActive && this.recordedChunks.length === 0) {
      throw new Error("No active recording to stop.");
    }

    this.isRecordingActive = false;
    const inputSampleRate = this.audioContext?.sampleRate || TARGET_SAMPLE_RATE;

    if (this.autoStopTimeout) {
      clearTimeout(this.autoStopTimeout);
      this.autoStopTimeout = null;
    }

    // Merge recorded chunks
    let totalLength = 0;
    for (const chunk of this.recordedChunks) {
      totalLength += chunk.length;
    }

    const mergedBuffer = new Float32Array(totalLength);
    let offset = 0;
    for (const chunk of this.recordedChunks) {
      mergedBuffer.set(chunk, offset);
      offset += chunk.length;
    }

    // Resample to 16 kHz
    const resampledBuffer = downsampleTo16kHz(mergedBuffer, inputSampleRate);

    // Encode to 16-bit PCM WAV
    const blob = encodePcmWav(resampledBuffer, TARGET_SAMPLE_RATE);
    const durationSeconds = resampledBuffer.length / TARGET_SAMPLE_RATE;

    this.cleanup();

    return {
      blob,
      durationSeconds: Math.round(durationSeconds * 100) / 100,
      sizeBytes: blob.size,
    };
  }

  /**
   * Cancels and discards active recording.
   */
  public cancel(): void {
    this.cleanup();
  }

  private cleanup(): void {
    this.isRecordingActive = false;
    this.recordedChunks = [];
    this.recordingStartTime = null;

    if (this.autoStopTimeout) {
      clearTimeout(this.autoStopTimeout);
      this.autoStopTimeout = null;
    }

    if (this.processor) {
      try {
        this.processor.disconnect();
        this.processor.onaudioprocess = null;
      } catch {
        // ignore
      }
      this.processor = null;
    }

    if (this.sourceNode) {
      try {
        this.sourceNode.disconnect();
      } catch {
        // ignore
      }
      this.sourceNode = null;
    }

    if (this.audioContext && this.audioContext.state !== "closed") {
      try {
        this.audioContext.close();
      } catch {
        // ignore
      }
      this.audioContext = null;
    }

    if (this.mediaStream) {
      try {
        this.mediaStream.getTracks().forEach((track) => track.stop());
      } catch {
        // ignore
      }
      this.mediaStream = null;
    }
  }
}
