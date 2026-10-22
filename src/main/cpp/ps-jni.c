// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
//
// The bridge between chiaki and com.tals.psbridge.PsNative.
//
// Written after CloudPad's chiaki-jni.c, with one difference that shapes all of
// it: nothing is decoded or shown here. The video access units go to Kotlin
// untouched, for the client app to decode wherever it likes; the audio is
// decoded from Opus to PCM, so the client needs no Opus decoder of its own.

#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

#include <chiaki/common.h>
#include <chiaki/log.h>
#include <chiaki/session.h>
#include <chiaki/opusdecoder.h>
#include <chiaki/regist.h>
#include <chiaki/streamconnection.h>
#include <chiaki/senkusha.h>
#include <netdb.h>
#include <netinet/in.h>
#include <chiaki/remote/holepunch.h>

#define TAG "PsNative"
#define JNI_FN(name) Java_com_tals_psbridge_PsNative_##name

static JavaVM *g_vm;
static pthread_key_t g_env_key;
static ChiakiLog g_log;

// ------------------------------------------------------------------ threads

// chiaki calls back from its own threads. Each one is attached once and detached
// when it ends (the key's destructor), rather than attached and detached around
// every call: the video callback runs for every frame.
static void detach_thread(void *unused)
{
	(void)unused;
	(*g_vm)->DetachCurrentThread(g_vm);
}

static JNIEnv *thread_env(void)
{
	JNIEnv *env = NULL;
	if((*g_vm)->GetEnv(g_vm, (void **)&env, JNI_VERSION_1_6) == JNI_OK)
		return env;
	if((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK)
		return NULL;
	pthread_setspecific(g_env_key, env);
	return env;
}

// ------------------------------------------------------------------ logging

static void log_cb(ChiakiLogLevel level, const char *msg, void *user)
{
	(void)user;
	int prio = ANDROID_LOG_INFO;
	switch(level)
	{
		case CHIAKI_LOG_ERROR: prio = ANDROID_LOG_ERROR; break;
		case CHIAKI_LOG_WARNING: prio = ANDROID_LOG_WARN; break;
		case CHIAKI_LOG_DEBUG:
		case CHIAKI_LOG_VERBOSE: prio = ANDROID_LOG_DEBUG; break;
		default: break;
	}
	__android_log_write(prio, "chiaki", msg);
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved)
{
	(void)reserved;
	g_vm = vm;
	pthread_key_create(&g_env_key, detach_thread);
	chiaki_log_init(&g_log, CHIAKI_LOG_INFO | CHIAKI_LOG_WARNING | CHIAKI_LOG_ERROR, log_cb, NULL);
	ChiakiErrorCode err = chiaki_lib_init();
	__android_log_print(ANDROID_LOG_INFO, TAG, "chiaki_lib_init: %s", chiaki_error_string(err));
	return JNI_VERSION_1_6;
}

/** curl on mbedTLS has no access to the system trust store: chiaki reads this bundle path. */
JNIEXPORT void JNICALL JNI_FN(setCaBundle)(JNIEnv *env, jclass cls, jstring path)
{
	(void)cls;
	const char *p = (*env)->GetStringUTFChars(env, path, NULL);
	if(!p)
		return;
	setenv("CHIAKI_CA_BUNDLE", p, 1);
	(*env)->ReleaseStringUTFChars(env, path, p);
}

JNIEXPORT jstring JNICALL JNI_FN(errorString)(JNIEnv *env, jclass cls, jint code)
{
	(void)cls;
	return (*env)->NewStringUTF(env, chiaki_error_string((ChiakiErrorCode)code));
}

JNIEXPORT jstring JNICALL JNI_FN(quitReasonString)(JNIEnv *env, jclass cls, jint reason)
{
	(void)cls;
	return (*env)->NewStringUTF(env, chiaki_quit_reason_string((ChiakiQuitReason)reason));
}

// ------------------------------------------------------------------ PSN devices

/**
 * The consoles of the PSN account behind [token], of one type. Returns them
 * flattened, four entries per console: name, uid (hex), "1"/"0" for Remote Play
 * enabled, and the type. null when the request failed.
 */
JNIEXPORT jobjectArray JNICALL JNI_FN(listDevices)(JNIEnv *env, jclass cls, jstring token_str, jint console_type)
{
	(void)cls;
	const char *token = (*env)->GetStringUTFChars(env, token_str, NULL);
	if(!token)
		return NULL;
	ChiakiHolepunchDeviceInfo *devices = NULL;
	size_t count = 0;
	ChiakiErrorCode err = chiaki_holepunch_list_devices(token, (ChiakiHolepunchConsoleType)console_type, &devices, &count, false, &g_log);
	(*env)->ReleaseStringUTFChars(env, token_str, token);
	if(err != CHIAKI_ERR_SUCCESS)
	{
		__android_log_print(ANDROID_LOG_WARN, TAG, "listDevices failed: %s", chiaki_error_string(err));
		return NULL;
	}

	jclass string_class = (*env)->FindClass(env, "java/lang/String");
	jobjectArray r = (*env)->NewObjectArray(env, (jsize)(count * 4), string_class, NULL);
	for(size_t i = 0; i < count; i++)
	{
		ChiakiHolepunchDeviceInfo *d = &devices[i];
		char name[sizeof(d->device_name) + 1];
		memcpy(name, d->device_name, sizeof(d->device_name));
		name[sizeof(d->device_name)] = '\0';
		// Names are user text; NewStringUTF wants modified UTF-8, so anything
		// outside ASCII is replaced rather than risked.
		for(char *c = name; *c; c++)
			if(*c & 0x80)
				*c = '?';
		char uid_hex[sizeof(d->device_uid) * 2 + 1];
		for(size_t b = 0; b < sizeof(d->device_uid); b++)
			snprintf(uid_hex + b * 2, 3, "%02x", d->device_uid[b]);
		char type[4];
		snprintf(type, sizeof(type), "%d", (int)d->type);

		(*env)->SetObjectArrayElement(env, r, (jsize)(i * 4), (*env)->NewStringUTF(env, name));
		(*env)->SetObjectArrayElement(env, r, (jsize)(i * 4 + 1), (*env)->NewStringUTF(env, uid_hex));
		(*env)->SetObjectArrayElement(env, r, (jsize)(i * 4 + 2), (*env)->NewStringUTF(env, d->remoteplay_enabled ? "1" : "0"));
		(*env)->SetObjectArrayElement(env, r, (jsize)(i * 4 + 3), (*env)->NewStringUTF(env, type));
	}
	chiaki_holepunch_free_device_list(&devices);
	return r;
}

// ------------------------------------------------------------------ hole punching
// One call per step, so the Kotlin side can give up between two of them.

JNIEXPORT jlong JNICALL JNI_FN(holepunchInit)(JNIEnv *env, jclass cls, jstring token_str)
{
	(void)cls;
	const char *token = (*env)->GetStringUTFChars(env, token_str, NULL);
	if(!token)
		return 0;
	ChiakiHolepunchSession s = chiaki_holepunch_session_init(token, &g_log);
	(*env)->ReleaseStringUTFChars(env, token_str, token);
	return (jlong)(intptr_t)s;
}

JNIEXPORT jint JNICALL JNI_FN(holepunchUpnp)(JNIEnv *env, jclass cls, jlong hp)
{
	(void)env; (void)cls;
	return hp ? chiaki_holepunch_upnp_discover((ChiakiHolepunchSession)(intptr_t)hp) : CHIAKI_ERR_INVALID_DATA;
}

JNIEXPORT jint JNICALL JNI_FN(holepunchCreate)(JNIEnv *env, jclass cls, jlong hp)
{
	(void)env; (void)cls;
	return hp ? chiaki_holepunch_session_create((ChiakiHolepunchSession)(intptr_t)hp) : CHIAKI_ERR_INVALID_DATA;
}

JNIEXPORT jint JNICALL JNI_FN(holepunchOffer)(JNIEnv *env, jclass cls, jlong hp)
{
	(void)env; (void)cls;
	return hp ? holepunch_session_create_offer((ChiakiHolepunchSession)(intptr_t)hp) : CHIAKI_ERR_INVALID_DATA;
}

JNIEXPORT jint JNICALL JNI_FN(holepunchStart)(JNIEnv *env, jclass cls, jlong hp, jbyteArray uid, jint console_type)
{
	(void)cls;
	if(!hp)
		return CHIAKI_ERR_INVALID_DATA;
	jsize len = (*env)->GetArrayLength(env, uid);
	uint8_t *bytes = malloc((size_t)len);
	if(!bytes)
		return CHIAKI_ERR_MEMORY;
	(*env)->GetByteArrayRegion(env, uid, 0, len, (jbyte *)bytes);
	ChiakiErrorCode err = chiaki_holepunch_session_start((ChiakiHolepunchSession)(intptr_t)hp, bytes, (ChiakiHolepunchConsoleType)console_type);
	free(bytes);
	return err;
}

JNIEXPORT jint JNICALL JNI_FN(holepunchPunch)(JNIEnv *env, jclass cls, jlong hp, jint port_type)
{
	(void)env; (void)cls;
	return hp ? chiaki_holepunch_session_punch_hole((ChiakiHolepunchSession)(intptr_t)hp, (ChiakiHolepunchPortType)port_type) : CHIAKI_ERR_INVALID_DATA;
}

JNIEXPORT jstring JNICALL JNI_FN(holepunchLastError)(JNIEnv *env, jclass cls, jlong hp)
{
	(void)cls;
	if(!hp)
		return NULL;
	char buf[256] = { 0 };
	chiaki_holepunch_session_get_last_error((ChiakiHolepunchSession)(intptr_t)hp, buf, sizeof(buf));
	for(char *c = buf; *c; c++)
		if(*c & 0x80)
			*c = '?';
	return (*env)->NewStringUTF(env, buf);
}

/** Interrupts a blocking step from another thread. */
JNIEXPORT void JNICALL JNI_FN(holepunchCancel)(JNIEnv *env, jclass cls, jlong hp)
{
	(void)env; (void)cls;
	if(hp)
		chiaki_holepunch_main_thread_cancel((ChiakiHolepunchSession)(intptr_t)hp, true);
}

/** Only for a hole punch that never reached [sessionCreate]: from then on the session owns it. */
JNIEXPORT void JNICALL JNI_FN(holepunchFini)(JNIEnv *env, jclass cls, jlong hp)
{
	(void)env; (void)cls;
	if(hp)
		chiaki_holepunch_session_fini((ChiakiHolepunchSession)(intptr_t)hp);
}

// ------------------------------------------------------------------ session

typedef struct ps_session_t
{
	ChiakiSession session;
	ChiakiOpusDecoder opus;
	jobject listener;
	jmethodID on_connected;
	jmethodID on_quit;
	jmethodID on_rumble;
	jmethodID on_video;
	jmethodID on_audio_format;
	jmethodID on_audio;
	uint32_t audio_channels;
	// Cloud sessions: chiaki keeps pointers to these for the session's whole life.
	char *cloud_launch_spec;
	char *cloud_handshake_key;
	char *cloud_session_id;
} PsSession;

static void event_cb(ChiakiEvent *event, void *user)
{
	PsSession *s = user;
	JNIEnv *env = thread_env();
	if(!env)
		return;
	switch(event->type)
	{
		case CHIAKI_EVENT_CONNECTED:
			(*env)->CallVoidMethod(env, s->listener, s->on_connected);
			break;
		case CHIAKI_EVENT_QUIT:
		{
			jstring reason = NULL;
			if(event->quit.reason_str)
			{
				char *copy = strdup(event->quit.reason_str);
				if(copy)
				{
					for(char *c = copy; *c; c++)
						if(*c & 0x80)
							*c = '?';
					reason = (*env)->NewStringUTF(env, copy);
					free(copy);
				}
			}
			(*env)->CallVoidMethod(env, s->listener, s->on_quit, (jint)event->quit.reason, reason);
			if(reason)
				(*env)->DeleteLocalRef(env, reason);
			break;
		}
		case CHIAKI_EVENT_RUMBLE:
			(*env)->CallVoidMethod(env, s->listener, s->on_rumble, (jint)event->rumble.left, (jint)event->rumble.right);
			break;
		default:
			break;
	}
	if((*env)->ExceptionCheck(env))
		(*env)->ExceptionClear(env);
}

static bool video_cb(uint8_t *buf, size_t buf_size, int32_t frames_lost, bool frame_recovered, void *user)
{
	(void)frame_recovered;
	PsSession *s = user;
	JNIEnv *env = thread_env();
	if(!env)
		return false;
	jbyteArray data = (*env)->NewByteArray(env, (jsize)buf_size);
	if(!data)
	{
		(*env)->ExceptionClear(env);
		return false;
	}
	(*env)->SetByteArrayRegion(env, data, 0, (jsize)buf_size, (const jbyte *)buf);
	(*env)->CallVoidMethod(env, s->listener, s->on_video, data, (jint)frames_lost);
	(*env)->DeleteLocalRef(env, data);
	if((*env)->ExceptionCheck(env))
	{
		(*env)->ExceptionClear(env);
		return false;
	}
	return true;
}

static void audio_settings_cb(uint32_t channels, uint32_t rate, void *user)
{
	PsSession *s = user;
	s->audio_channels = channels;
	JNIEnv *env = thread_env();
	if(!env)
		return;
	(*env)->CallVoidMethod(env, s->listener, s->on_audio_format, (jint)channels, (jint)rate);
	if((*env)->ExceptionCheck(env))
		(*env)->ExceptionClear(env);
}

/** One decoded Opus frame: [samples_count] frames of interleaved 16-bit PCM, handed over as little-endian bytes. */
static void audio_frame_cb(int16_t *pcm, size_t samples_count, void *user)
{
	PsSession *s = user;
	JNIEnv *env = thread_env();
	if(!env || s->audio_channels == 0)
		return;
	size_t bytes = samples_count * s->audio_channels * sizeof(int16_t);
	jbyteArray data = (*env)->NewByteArray(env, (jsize)bytes);
	if(!data)
	{
		(*env)->ExceptionClear(env);
		return;
	}
	(*env)->SetByteArrayRegion(env, data, 0, (jsize)bytes, (const jbyte *)pcm);
	(*env)->CallVoidMethod(env, s->listener, s->on_audio, data);
	(*env)->DeleteLocalRef(env, data);
	if((*env)->ExceptionCheck(env))
		(*env)->ExceptionClear(env);
}

/**
 * Sets up [info] as a session, wires its callbacks to [listener], and returns the
 * native handle or 0. [s] was allocated by the caller and is freed here on failure.
 */
static jlong session_create(JNIEnv *env, PsSession *s, ChiakiConnectInfo *info, jobject listener)
{
	ChiakiErrorCode err = chiaki_session_init(&s->session, info, &g_log);
	if(err != CHIAKI_ERR_SUCCESS)
	{
		// chiaki_session_init finishes a hole punch on its own failure paths.
		__android_log_print(ANDROID_LOG_ERROR, TAG, "chiaki_session_init: %s", chiaki_error_string(err));
		free(s->cloud_launch_spec);
		free(s->cloud_handshake_key);
		free(s->cloud_session_id);
		free(s);
		return 0;
	}

	s->listener = (*env)->NewGlobalRef(env, listener);
	jclass lc = (*env)->GetObjectClass(env, listener);
	s->on_connected = (*env)->GetMethodID(env, lc, "onConnected", "()V");
	s->on_quit = (*env)->GetMethodID(env, lc, "onQuit", "(ILjava/lang/String;)V");
	s->on_rumble = (*env)->GetMethodID(env, lc, "onRumble", "(II)V");
	s->on_video = (*env)->GetMethodID(env, lc, "onVideo", "([BI)V");
	s->on_audio_format = (*env)->GetMethodID(env, lc, "onAudioFormat", "(II)V");
	s->on_audio = (*env)->GetMethodID(env, lc, "onAudio", "([B)V");

	chiaki_opus_decoder_init(&s->opus, &g_log);
	chiaki_opus_decoder_set_cb(&s->opus, audio_settings_cb, audio_frame_cb, s);
	ChiakiAudioSink sink;
	chiaki_opus_decoder_get_sink(&s->opus, &sink);
	chiaki_session_set_audio_sink(&s->session, &sink);
	chiaki_session_set_event_cb(&s->session, event_cb, s);
	chiaki_session_set_video_sample_cb(&s->session, video_cb, s);
	return (jlong)(intptr_t)s;
}

static void video_profile(ChiakiConnectInfo *info, jint width, jint height, jint fps, jint bitrate_kbps, jint codec)
{
	info->video_profile.width = (unsigned int)width;
	info->video_profile.height = (unsigned int)height;
	info->video_profile.max_fps = (unsigned int)fps;
	info->video_profile.bitrate = (unsigned int)bitrate_kbps;
	info->video_profile.codec = (ChiakiCodec)codec;
	info->video_profile_auto_downgrade = true;
	// Report loss up to 5 %: the server then lowers its bitrate on a weak link
	// (mobile data on the road) instead of pushing on through it.
	info->packet_loss_max = 0.05;
}

static char *dup_jstring(JNIEnv *env, jstring str)
{
	if(!str)
		return NULL;
	const char *c = (*env)->GetStringUTFChars(env, str, NULL);
	if(!c)
		return NULL;
	char *r = strdup(c);
	(*env)->ReleaseStringUTFChars(env, str, c);
	return r;
}

/**
 * A Remote Play session over the hole punched by the steps above, which it takes
 * over. [codec] is chiaki's (0 = H.264, 1 = H.265). Returns the native handle, or
 * 0 when the session could not be set up; the hole punch is finished either way.
 */
JNIEXPORT jlong JNICALL JNI_FN(sessionCreate)(JNIEnv *env, jclass cls, jlong hp, jbyteArray account_id,
		jboolean ps5, jint width, jint height, jint fps, jint bitrate_kbps, jint codec, jobject listener)
{
	(void)cls;
	ChiakiConnectInfo info;
	memset(&info, 0, sizeof(info));
	info.ps5 = ps5;
	info.host = "";
	info.holepunch_session = (ChiakiHolepunchSession)(intptr_t)hp;
	info.service_type = CHIAKI_SERVICE_TYPE_REMOTE_PLAY;
	video_profile(&info, width, height, fps, bitrate_kbps, codec);
	if((*env)->GetArrayLength(env, account_id) == CHIAKI_PSN_ACCOUNT_ID_SIZE)
		(*env)->GetByteArrayRegion(env, account_id, 0, CHIAKI_PSN_ACCOUNT_ID_SIZE, (jbyte *)info.psn_account_id);

	PsSession *s = calloc(1, sizeof(PsSession));
	if(!s)
	{
		chiaki_holepunch_session_fini(info.holepunch_session);
		return 0;
	}
	return session_create(env, s, &info, listener);
}

/**
 * A PlayStation Plus cloud session on the datacenter machine Sony allocated
 * (see ps/cloud): its address and port, and the launch spec, handshake key and
 * session id the allocation returned. [pscloud]: a PS5 cloud game, else a PS Now
 * one (PS4/PS3 classics). [rtt_us] and the MTUs come from the datacenter ping;
 * 0 lets chiaki use its defaults.
 */
JNIEXPORT jlong JNICALL JNI_FN(sessionCreateCloud)(JNIEnv *env, jclass cls,
		jstring host, jint port, jstring launch_spec, jstring handshake_key, jstring session_id,
		jint wrapper_type, jint mtu_in, jint mtu_out, jlong rtt_us, jboolean pscloud,
		jboolean ps5, jint width, jint height, jint fps, jint bitrate_kbps, jint codec, jobject listener)
{
	(void)cls;
	PsSession *s = calloc(1, sizeof(PsSession));
	if(!s)
		return 0;
	s->cloud_launch_spec = dup_jstring(env, launch_spec);
	s->cloud_handshake_key = dup_jstring(env, handshake_key);
	s->cloud_session_id = dup_jstring(env, session_id);
	char *host_str = dup_jstring(env, host);

	ChiakiConnectInfo info;
	memset(&info, 0, sizeof(info));
	info.ps5 = ps5;
	info.host = host_str ? host_str : "";
	info.service_type = pscloud ? CHIAKI_SERVICE_TYPE_PSCLOUD : CHIAKI_SERVICE_TYPE_PSNOW;
	info.cloud_launch_spec = s->cloud_launch_spec;
	info.cloud_handshake_key = s->cloud_handshake_key;
	info.cloud_session_id = s->cloud_session_id;
	info.cloud_port = (uint16_t)port;
	info.cloud_psn_wrapper_type = (uint8_t)wrapper_type;
	info.cloud_mtu_in = (uint32_t)mtu_in;
	info.cloud_mtu_out = (uint32_t)mtu_out;
	info.cloud_rtt_us = (uint64_t)rtt_us;
	video_profile(&info, width, height, fps, bitrate_kbps, codec);

	jlong r = session_create(env, s, &info, listener);
	// The host is resolved during init and not kept.
	free(host_str);
	return r;
}

JNIEXPORT jint JNICALL JNI_FN(sessionStart)(JNIEnv *env, jclass cls, jlong ptr)
{
	(void)env; (void)cls;
	return chiaki_session_start(&((PsSession *)(intptr_t)ptr)->session);
}

JNIEXPORT jint JNICALL JNI_FN(sessionStop)(JNIEnv *env, jclass cls, jlong ptr)
{
	(void)env; (void)cls;
	return chiaki_session_stop(&((PsSession *)(intptr_t)ptr)->session);
}

/** Waits for the session thread to end; call after [sessionStop], before [sessionFree]. */
JNIEXPORT jint JNICALL JNI_FN(sessionJoin)(JNIEnv *env, jclass cls, jlong ptr)
{
	(void)env; (void)cls;
	return chiaki_session_join(&((PsSession *)(intptr_t)ptr)->session);
}

JNIEXPORT void JNICALL JNI_FN(sessionFree)(JNIEnv *env, jclass cls, jlong ptr)
{
	(void)cls;
	PsSession *s = (PsSession *)(intptr_t)ptr;
	if(!s)
		return;
	chiaki_session_fini(&s->session);
	chiaki_opus_decoder_fini(&s->opus);
	(*env)->DeleteGlobalRef(env, s->listener);
	free(s->cloud_launch_spec);
	free(s->cloud_handshake_key);
	free(s->cloud_session_id);
	free(s);
}

/**
 * The whole pad at once. Sticks are signed 16-bit with up = negative on the y
 * axes, as on a DualSense; triggers 0..255.
 */
JNIEXPORT void JNICALL JNI_FN(sessionSetController)(JNIEnv *env, jclass cls, jlong ptr,
		jint buttons, jint l2, jint r2, jint lx, jint ly, jint rx, jint ry)
{
	(void)env; (void)cls;
	PsSession *s = (PsSession *)(intptr_t)ptr;
	ChiakiControllerState state;
	chiaki_controller_state_set_idle(&state);
	state.buttons = (uint32_t)buttons;
	state.l2_state = (uint8_t)l2;
	state.r2_state = (uint8_t)r2;
	state.left_x = (int16_t)lx;
	state.left_y = (int16_t)ly;
	state.right_x = (int16_t)rx;
	state.right_y = (int16_t)ry;
	chiaki_session_set_controller_state(&s->session, &state);
}

/** Asks the console for a keyframe: a viewer joined, or fell behind and skipped ahead. */
JNIEXPORT jint JNICALL JNI_FN(sessionRequestIdr)(JNIEnv *env, jclass cls, jlong ptr)
{
	(void)env; (void)cls;
	return stream_connection_send_idr_request(&((PsSession *)(intptr_t)ptr)->session.stream_connection);
}

/** Puts the console in rest mode, which also ends the session. */
JNIEXPORT jint JNICALL JNI_FN(sessionGotoBed)(JNIEnv *env, jclass cls, jlong ptr)
{
	(void)env; (void)cls;
	return chiaki_session_goto_bed(&((PsSession *)(intptr_t)ptr)->session);
}

// ------------------------------------------------------------------ cloud datacenter ping

/**
 * Measures one PS Plus datacenter the way CloudPad does: a senkusha handshake
 * (protocol 9, the Gaikai session key as launch spec) and its echo test.
 * Returns {rtt_us, mtu_in, mtu_out}, rtt_us = -1 on failure. Blocks for up to
 * a few seconds.
 */
JNIEXPORT jlongArray JNICALL JNI_FN(pingDatacenter)(JNIEnv *env, jclass cls, jstring ip, jint port, jstring session_key, jboolean pscloud)
{
	(void)cls;
	jlong out[3] = { -1, 0, 0 };
	jlongArray result = (*env)->NewLongArray(env, 3);
	char *ip_str = dup_jstring(env, ip);
	char *key_str = dup_jstring(env, session_key);
	if(!result || !ip_str || !key_str)
		goto done;

	struct addrinfo hints;
	memset(&hints, 0, sizeof(hints));
	hints.ai_family = AF_INET;
	hints.ai_socktype = SOCK_DGRAM;
	hints.ai_protocol = IPPROTO_UDP;
	char port_str[16];
	snprintf(port_str, sizeof(port_str), "%d", (int)port);
	struct addrinfo *addr = NULL;
	if(getaddrinfo(ip_str, port_str, &hints, &addr) != 0 || !addr)
	{
		__android_log_print(ANDROID_LOG_WARN, TAG, "Datacenter %s:%d does not resolve", ip_str, (int)port);
		goto done;
	}

	// senkusha only reads these few fields of the session.
	ChiakiSession *session = calloc(1, sizeof(ChiakiSession));
	if(!session)
	{
		freeaddrinfo(addr);
		goto done;
	}
	session->log = &g_log;
	session->connect_info.host_addrinfo_selected = addr;
	session->connect_info.enable_dualsense = false;
	session->target = CHIAKI_TARGET_PS5_1;
	session->cloud_port = (uint16_t)port;
	session->service_type = pscloud ? CHIAKI_SERVICE_TYPE_PSCLOUD : CHIAKI_SERVICE_TYPE_PSNOW;
	session->cloud_psn_wrapper_type = pscloud ? 0 : 0x01;

	ChiakiSenkusha senkusha;
	if(chiaki_senkusha_init(&senkusha, session) == CHIAKI_ERR_SUCCESS)
	{
		senkusha.protocol_version = 9;
		senkusha.cloud_launch_spec = key_str;
		uint32_t mtu_in = 0, mtu_out = 0;
		uint64_t rtt_us = 0;
		ChiakiErrorCode err = chiaki_senkusha_run(&senkusha, &mtu_in, &mtu_out, &rtt_us, NULL);
		senkusha.cloud_launch_spec = NULL;
		chiaki_senkusha_fini(&senkusha);
		if(err == CHIAKI_ERR_SUCCESS)
		{
			out[0] = (jlong)rtt_us;
			out[1] = (jlong)mtu_in;
			out[2] = (jlong)mtu_out;
		}
		__android_log_print(ANDROID_LOG_INFO, TAG, "Datacenter %s:%d: %s, rtt %lld us", ip_str, (int)port,
				chiaki_error_string(err), (long long)rtt_us);
	}
	freeaddrinfo(addr);
	free(session);

done:
	free(ip_str);
	free(key_str);
	if(result)
		(*env)->SetLongArrayRegion(env, result, 0, 3, out);
	return result;
}
