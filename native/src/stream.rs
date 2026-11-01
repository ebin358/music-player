//! Plain audio streams: YouTube Music's AAC (itag 140) and local files.
//!
//! Desktop only in practice. On Android ExoPlayer plays these; on the Mac there
//! is no ExoPlayer, and the JVM has no AAC decoder, so the stream is decoded
//! here with Symphonia (already in the tree for the DJ) and handed to the same
//! Kotlin `AudioOutput` the Spotify sink writes to. One audio path for both
//! sources, and the output's blocking `write` paces the decoder, exactly as it
//! does for librespot.
//!
//! The network side asks for the file in 1 MiB ranges rather than in one
//! request: YouTube throttles a single long download to roughly real time, and
//! ranged requests are what its own web client makes. Chunks are kept, so a
//! seek backwards never refetches.

use jni::objects::{GlobalRef, JValue};
use std::collections::HashMap;
use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::sync::atomic::{AtomicBool, AtomicI64, AtomicU64, AtomicU8, Ordering};
use std::sync::Mutex;
use std::time::Duration;

use symphonia::core::audio::SampleBuffer;
use symphonia::core::codecs::{DecoderOptions, CODEC_TYPE_NULL};
use symphonia::core::errors::Error as SymError;
use symphonia::core::formats::{FormatOptions, SeekMode, SeekTo};
use symphonia::core::io::{MediaSource, MediaSourceStream, MediaSourceStreamOptions};
use symphonia::core::meta::MetadataOptions;
use symphonia::core::probe::Hint;
use symphonia::core::units::Time;

use crate::engine::JAVA_VM;

pub const IDLE: u8 = 0;
pub const LOADING: u8 = 1;
pub const PLAYING: u8 = 2;
pub const PAUSED: u8 = 3;
pub const ENDED: u8 = 4;
pub const FAILED: u8 = 5;

/// Bumped by every `play` and `stop`; a decoder thread whose number is stale
/// winds down at its next packet.
static GENERATION: AtomicU64 = AtomicU64::new(0);
static STATE: AtomicU8 = AtomicU8::new(IDLE);
static PAUSED_FLAG: AtomicBool = AtomicBool::new(false);
static POSITION_MS: AtomicI64 = AtomicI64::new(0);
static DURATION_MS: AtomicI64 = AtomicI64::new(0);
/// A pending seek in milliseconds, or -1.
static SEEK_MS: AtomicI64 = AtomicI64::new(-1);
static ERROR: Mutex<String> = Mutex::new(String::new());

const CHUNK: u64 = 1 << 20;

pub fn state() -> u8 {
    STATE.load(Ordering::Acquire)
}
pub fn position_ms() -> i64 {
    POSITION_MS.load(Ordering::Acquire)
}
pub fn duration_ms() -> i64 {
    DURATION_MS.load(Ordering::Acquire)
}
pub fn last_error() -> String {
    ERROR.lock().map(|e| e.clone()).unwrap_or_default()
}
pub fn set_paused(paused: bool) {
    PAUSED_FLAG.store(paused, Ordering::Release);
    let s = state();
    if s == PLAYING || s == PAUSED {
        STATE.store(if paused { PAUSED } else { PLAYING }, Ordering::Release);
    }
}
pub fn seek(ms: i64) {
    SEEK_MS.store(ms.max(0), Ordering::Release);
    POSITION_MS.store(ms.max(0), Ordering::Release);
}
pub fn stop() {
    GENERATION.fetch_add(1, Ordering::AcqRel);
    STATE.store(IDLE, Ordering::Release);
    POSITION_MS.store(0, Ordering::Release);
}

/// Starts `source` (an https URL, a file:// URL or a plain path) from
/// `start_ms`, writing PCM to `output`. Returns at once; progress is read back
/// through [`state`] and [`position_ms`].
pub fn play(source: String, start_ms: i64, output: GlobalRef) {
    let generation = GENERATION.fetch_add(1, Ordering::AcqRel) + 1;
    STATE.store(LOADING, Ordering::Release);
    PAUSED_FLAG.store(false, Ordering::Release);
    POSITION_MS.store(start_ms.max(0), Ordering::Release);
    DURATION_MS.store(0, Ordering::Release);
    SEEK_MS.store(if start_ms > 0 { start_ms } else { -1 }, Ordering::Release);
    if let Ok(mut e) = ERROR.lock() {
        e.clear();
    }

    let spawned = std::thread::Builder::new()
        .name("square-stream".into())
        .spawn(move || {
            let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                run(&source, generation, &output)
            }));
            if GENERATION.load(Ordering::Acquire) != generation {
                return;
            }
            match result {
                Ok(Ok(())) => STATE.store(ENDED, Ordering::Release),
                Ok(Err(message)) => fail(message),
                Err(_) => fail("the decoder panicked".into()),
            }
        });
    if spawned.is_err() {
        fail("could not start the decoder thread".into());
    }
}

fn fail(message: String) {
    log::warn!("stream failed: {message}");
    if let Ok(mut e) = ERROR.lock() {
        *e = message;
    }
    STATE.store(FAILED, Ordering::Release);
}

fn live(generation: u64) -> bool {
    GENERATION.load(Ordering::Acquire) == generation
}

fn open(source: &str) -> Result<(Box<dyn MediaSource>, Hint), String> {
    let mut hint = Hint::new();
    if source.starts_with("http://") || source.starts_with("https://") {
        hint.mime_type("audio/mp4");
        hint.with_extension("m4a");
        return Ok((Box::new(HttpSource::new(source.to_string())?), hint));
    }
    let path = source.strip_prefix("file://").unwrap_or(source);
    let path = percent_decode(path);
    if let Some(ext) = std::path::Path::new(&path).extension().and_then(|e| e.to_str()) {
        hint.with_extension(ext);
    }
    let file = File::open(&path).map_err(|e| format!("cannot open {path}: {e}"))?;
    Ok((Box::new(file), hint))
}

fn run(source: &str, generation: u64, output: &GlobalRef) -> Result<(), String> {
    let (media, hint) = open(source)?;
    let stream = MediaSourceStream::new(media, MediaSourceStreamOptions::default());
    let probed = symphonia::default::get_probe()
        .format(
            &hint,
            stream,
            &FormatOptions { enable_gapless: true, ..Default::default() },
            &MetadataOptions::default(),
        )
        .map_err(|e| format!("unrecognised audio: {e}"))?;
    let mut format = probed.format;

    let track = format
        .tracks()
        .iter()
        .find(|t| t.codec_params.codec != CODEC_TYPE_NULL)
        .ok_or("no audio track")?
        .clone();
    let track_id = track.id;
    let params = &track.codec_params;
    let mut sample_rate = params.sample_rate.unwrap_or(44_100);
    if let (Some(frames), Some(tb)) = (params.n_frames, params.time_base) {
        let t = tb.calc_time(frames);
        DURATION_MS.store((t.seconds as f64 * 1000.0 + t.frac * 1000.0) as i64, Ordering::Release);
    }

    let mut decoder = symphonia::default::get_codecs()
        .make(params, &DecoderOptions::default())
        .map_err(|e| format!("unsupported codec: {e}"))?;

    let vm = JAVA_VM.get().ok_or("JNI_OnLoad did not run")?;
    let mut env = vm
        .attach_current_thread_permanently()
        .map_err(|e| format!("attach failed: {e}"))?;

    let mut samples: Option<SampleBuffer<i16>> = None;
    let mut pcm: Vec<u8> = Vec::new();
    let mut started = false;
    let mut frames_played: u64 = 0;
    let mut base_ms: i64 = 0;

    loop {
        if !live(generation) {
            let _ = env.call_method(output, "stop", "()V", &[]);
            return Ok(());
        }

        let target = SEEK_MS.swap(-1, Ordering::AcqRel);
        if target >= 0 {
            let time = Time::new((target / 1000) as u64, (target % 1000) as f64 / 1000.0);
            match format.seek(SeekMode::Accurate, SeekTo::Time { time, track_id: Some(track_id) }) {
                Ok(seeked) => {
                    decoder.reset();
                    let actual = track
                        .codec_params
                        .time_base
                        .map(|tb| {
                            let t = tb.calc_time(seeked.actual_ts);
                            t.seconds as i64 * 1000 + (t.frac * 1000.0) as i64
                        })
                        .unwrap_or(target);
                    base_ms = actual;
                    frames_played = 0;
                    POSITION_MS.store(actual, Ordering::Release);
                    // Throw away the second or so the output still holds.
                    let _ = env.call_method(output, "stop", "()V", &[]);
                    started = false;
                }
                Err(e) => log::warn!("seek failed: {e}"),
            }
        }

        if PAUSED_FLAG.load(Ordering::Acquire) {
            if started {
                let _ = env.call_method(output, "pause", "()V", &[]);
                started = false;
            }
            std::thread::sleep(Duration::from_millis(30));
            continue;
        }

        let packet = match format.next_packet() {
            Ok(p) => p,
            Err(SymError::IoError(e)) if e.kind() == std::io::ErrorKind::UnexpectedEof => {
                let _ = env.call_method(output, "drain", "()V", &[]);
                return Ok(());
            }
            Err(SymError::ResetRequired) => {
                decoder.reset();
                continue;
            }
            Err(e) => return Err(format!("read failed: {e}")),
        };
        if packet.track_id() != track_id {
            continue;
        }

        let decoded = match decoder.decode(&packet) {
            Ok(d) => d,
            Err(SymError::DecodeError(e)) => {
                log::debug!("skipping a bad frame: {e}");
                continue;
            }
            Err(e) => return Err(format!("decode failed: {e}")),
        };

        let spec = *decoded.spec();
        sample_rate = spec.rate;
        let channels = spec.channels.count().max(1);
        let frames = decoded.frames();
        let needed = decoded.capacity();
        if samples.as_ref().map(|s| s.capacity() < needed).unwrap_or(true) {
            samples = Some(SampleBuffer::<i16>::new(needed as u64, spec));
        }
        let buffer = samples.as_mut().unwrap();
        buffer.copy_interleaved_ref(decoded);

        pcm.clear();
        for s in buffer.samples() {
            pcm.extend_from_slice(&s.to_le_bytes());
        }
        if pcm.is_empty() || !live(generation) {
            continue;
        }

        if !started {
            env.call_method(output, "start", "()V", &[])
                .map_err(|e| format!("start failed: {e}"))?;
            started = true;
            STATE.store(PLAYING, Ordering::Release);
        }

        let direct = unsafe {
            env.new_direct_byte_buffer(pcm.as_mut_ptr(), pcm.len())
                .map_err(|e| e.to_string())?
        };
        env.call_method(
            output,
            "write",
            "(Ljava/nio/ByteBuffer;III)V",
            &[
                JValue::Object(&direct),
                JValue::Int(pcm.len() as i32),
                JValue::Int(sample_rate as i32),
                JValue::Int(channels as i32),
            ],
        )
        .map_err(|e| format!("write failed: {e}"))?;
        let _ = env.delete_local_ref(direct);
        if env.exception_check().unwrap_or(false) {
            let _ = env.exception_describe();
            let _ = env.exception_clear();
        }

        frames_played += frames as u64;
        if SEEK_MS.load(Ordering::Acquire) < 0 {
            POSITION_MS.store(
                base_ms + (frames_played * 1000 / sample_rate.max(1) as u64) as i64,
                Ordering::Release,
            );
        }
    }
}

fn percent_decode(s: &str) -> String {
    let bytes = s.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            if let Ok(b) = u8::from_str_radix(&s[i + 1..i + 3], 16) {
                out.push(b);
                i += 3;
                continue;
            }
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

/// A remote file read in ranged chunks, seekable, with every chunk kept.
struct HttpSource {
    url: String,
    client: reqwest::blocking::Client,
    len: u64,
    pos: u64,
    chunks: HashMap<u64, Vec<u8>>,
}

impl HttpSource {
    fn new(url: String) -> Result<Self, String> {
        let client = reqwest::blocking::Client::builder()
            .timeout(Duration::from_secs(30))
            .user_agent("Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36")
            .build()
            .map_err(|e| e.to_string())?;
        let mut source = Self { url, client, len: 0, pos: 0, chunks: HashMap::new() };
        // The first chunk also tells us how long the file is.
        let first = source.fetch(0)?;
        source.chunks.insert(0, first);
        Ok(source)
    }

    fn fetch(&mut self, index: u64) -> Result<Vec<u8>, String> {
        let start = index * CHUNK;
        let end = start + CHUNK - 1;
        let mut last = String::new();
        for attempt in 0..3 {
            if attempt > 0 {
                std::thread::sleep(Duration::from_millis(400 * attempt));
            }
            let response = match self
                .client
                .get(&self.url)
                .header("Range", format!("bytes={start}-{end}"))
                .send()
            {
                Ok(r) => r,
                Err(e) => {
                    last = e.to_string();
                    continue;
                }
            };
            let status = response.status();
            if !status.is_success() {
                last = format!("HTTP {status}");
                if status.as_u16() == 403 || status.as_u16() == 410 {
                    break; // an expired URL does not recover
                }
                continue;
            }
            if self.len == 0 {
                let total = response
                    .headers()
                    .get("content-range")
                    .and_then(|v| v.to_str().ok())
                    .and_then(|v| v.rsplit('/').next())
                    .and_then(|v| v.parse::<u64>().ok())
                    .or_else(|| response.content_length());
                self.len = total.unwrap_or(0);
            }
            match response.bytes() {
                Ok(b) => return Ok(b.to_vec()),
                Err(e) => last = e.to_string(),
            }
        }
        Err(format!("download failed: {last}"))
    }
}

impl Read for HttpSource {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        if self.len > 0 && self.pos >= self.len {
            return Ok(0);
        }
        let index = self.pos / CHUNK;
        if !self.chunks.contains_key(&index) {
            let data = self
                .fetch(index)
                .map_err(|e| std::io::Error::new(std::io::ErrorKind::Other, e))?;
            // Keep memory bounded on very long streams: drop chunks far behind.
            if self.chunks.len() > 64 {
                let keep_from = index.saturating_sub(8);
                self.chunks.retain(|k, _| *k + 1 >= keep_from || *k == 0);
            }
            self.chunks.insert(index, data);
        }
        let chunk = &self.chunks[&index];
        let offset = (self.pos - index * CHUNK) as usize;
        if offset >= chunk.len() {
            return Ok(0);
        }
        let n = buf.len().min(chunk.len() - offset);
        buf[..n].copy_from_slice(&chunk[offset..offset + n]);
        self.pos += n as u64;
        Ok(n)
    }
}

impl Seek for HttpSource {
    fn seek(&mut self, pos: SeekFrom) -> std::io::Result<u64> {
        let next = match pos {
            SeekFrom::Start(p) => p as i64,
            SeekFrom::End(p) => self.len as i64 + p,
            SeekFrom::Current(p) => self.pos as i64 + p,
        };
        if next < 0 {
            return Err(std::io::Error::new(std::io::ErrorKind::InvalidInput, "negative seek"));
        }
        self.pos = next as u64;
        Ok(self.pos)
    }
}

impl MediaSource for HttpSource {
    fn is_seekable(&self) -> bool {
        true
    }
    fn byte_len(&self) -> Option<u64> {
        (self.len > 0).then_some(self.len)
    }
}

// --- JNI: `dev.lelonio.square.nativecore.StreamBridge` (desktop) -----------

mod jni_exports {
    use jni::objects::{JClass, JObject, JString};
    use jni::sys::{jboolean, jint, jlong, jstring, JNI_TRUE};
    use jni::JNIEnv;

    #[no_mangle]
    pub extern "system" fn Java_dev_lelonio_square_nativecore_StreamBridge_nativePlay(
        mut env: JNIEnv,
        _class: JClass,
        source: JString,
        start_ms: jlong,
        output: JObject,
    ) {
        let source: String = match env.get_string(&source) {
            Ok(s) => s.into(),
            Err(e) => {
                let _ = env.throw_new("java/lang/IllegalArgumentException", e.to_string());
                return;
            }
        };
        match env.new_global_ref(&output) {
            Ok(output) => super::play(source, start_ms, output),
            Err(e) => {
                let _ = env.throw_new("java/lang/IllegalStateException", e.to_string());
            }
        }
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_lelonio_square_nativecore_StreamBridge_nativeSetPaused(
        _env: JNIEnv,
        _class: JClass,
        paused: jboolean,
    ) {
        super::set_paused(paused == JNI_TRUE);
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_lelonio_square_nativecore_StreamBridge_nativeSeek(
        _env: JNIEnv,
        _class: JClass,
        position_ms: jlong,
    ) {
        super::seek(position_ms);
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_lelonio_square_nativecore_StreamBridge_nativeStop(
        _env: JNIEnv,
        _class: JClass,
    ) {
        super::stop();
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_lelonio_square_nativecore_StreamBridge_nativeState(
        _env: JNIEnv,
        _class: JClass,
    ) -> jint {
        super::state() as jint
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_lelonio_square_nativecore_StreamBridge_nativePosition(
        _env: JNIEnv,
        _class: JClass,
    ) -> jlong {
        super::position_ms()
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_lelonio_square_nativecore_StreamBridge_nativeDuration(
        _env: JNIEnv,
        _class: JClass,
    ) -> jlong {
        super::duration_ms()
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_lelonio_square_nativecore_StreamBridge_nativeError(
        env: JNIEnv,
        _class: JClass,
    ) -> jstring {
        env.new_string(super::last_error())
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut())
    }
}
