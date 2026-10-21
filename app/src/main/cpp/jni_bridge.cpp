#include <android/log.h>
#include <jni.h>
#include <iomanip>
#include <cmath>
#include <string>
#include <sstream>
#include <vector>
#include <unistd.h>
#include <sampling.h>

#include "logging.h"
#include "chat.h"
#include "common.h"
#include "llama.h"

template<class T>
static std::string join(const std::vector<T> & values, const std::string & delim) {
    std::ostringstream str;
    for (size_t i = 0; i < values.size(); i++) {
        str << values[i];
        if (i < values.size() - 1) { str << delim; }
    }
    return str.str();
}

constexpr int   N_THREADS_MIN        = 2;
constexpr int   N_THREADS_MAX        = 4;
constexpr int   N_THREADS_HEADROOM   = 2;

constexpr int   DEFAULT_CONTEXT_SIZE = 4096;
constexpr int   OVERFLOW_HEADROOM    = 4;
constexpr int   BATCH_SIZE           = 512;

static llama_model              * g_model;
static llama_context            * g_context;
static common_batch               g_batch;
static common_chat_templates_ptr  g_chat_templates;
static common_sampler           * g_sampler;

// Context size actually used by the live context. Read from llama_n_ctx() after
// prepare() so shifting/overflow logic follows the caller's request instead of
// a compile-time constant.
static int g_n_ctx = DEFAULT_CONTEXT_SIZE;

static void release_sampler() {
    if (g_sampler) {
        common_sampler_free(g_sampler);
        g_sampler = nullptr;
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_chatai_llama_LlamaEngineImpl_init(JNIEnv * env, jobject /*unused*/, jstring native_lib_dir) {
    llama_log_set(aichat_android_log_callback, nullptr);

    const auto * path_to_backend = env->GetStringUTFChars(native_lib_dir, nullptr);
    LOGi("Native library dir: %s", path_to_backend);
    env->ReleaseStringUTFChars(native_lib_dir, path_to_backend);

    // Static build: the CPU backend is linked in, so load_all only registers it.
    ggml_backend_load_all();

    llama_backend_init();
    LOGi("Backend initiated; log handler set.");
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_example_chatai_llama_LlamaEngineImpl_load(JNIEnv * env, jobject /*unused*/, jstring jmodel_path) {
    llama_model_params model_params = llama_model_default_params();

    const auto * model_path = env->GetStringUTFChars(jmodel_path, nullptr);
    LOGi("%s: loading model from %s", __func__, model_path);

    auto * model = llama_model_load_from_file(model_path, model_params);
    env->ReleaseStringUTFChars(jmodel_path, model_path);

    if (!model) {
        LOGe("%s: llama_model_load_from_file returned null", __func__);
        return 1;
    }

    if (g_model) {
        llama_model_free(g_model);
    }
    g_model = model;
    LOGi("%s: model loaded", __func__);
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_example_chatai_llama_LlamaEngineImpl_prepare(
        JNIEnv * /*env*/, jobject /*unused*/, jint n_ctx, jint n_threads) {
    if (!g_model) {
        LOGe("%s: model cannot be null", __func__);
        return 1;
    }

    int threads = n_threads > 0
            ? n_threads
            : std::max(N_THREADS_MIN, std::min(N_THREADS_MAX,
                       (int) sysconf(_SC_NPROCESSORS_ONLN) - N_THREADS_HEADROOM));

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx           = (uint32_t) n_ctx;
    ctx_params.n_batch         = BATCH_SIZE;
    ctx_params.n_ubatch        = BATCH_SIZE;
    ctx_params.n_threads       = threads;
    ctx_params.n_threads_batch = threads;

    auto * context = llama_init_from_model(g_model, ctx_params);
    if (!context) {
        LOGe("%s: llama_init_from_model returned null", __func__);
        return 2;
    }

    if (g_context) {
        llama_free(g_context);
    }
    g_context = context;
    g_n_ctx   = (int) llama_n_ctx(g_context);

    g_batch          = common_batch(g_context);
    g_chat_templates = common_chat_templates_init(g_model, "");

    // Sampling must be ready before the first prompt; setSampling only rebuilds it.
    release_sampler();
    common_params_sampling default_sampling;
    default_sampling.temp  = 0.8f;
    default_sampling.top_k = 40;
    default_sampling.top_p = 0.95f;
    g_sampler = common_sampler_init(g_model, default_sampling);
    if (!g_sampler) {
        LOGe("%s: common_sampler_init returned null", __func__);
        return 3;
    }

    LOGi("%s: context ready (n_ctx=%d, threads=%d)", __func__, g_n_ctx, threads);
    return 0;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_chatai_llama_LlamaEngineImpl_nativeSetSampling(
        JNIEnv * /*env*/, jobject /*unused*/, jfloat temp, jint top_k, jfloat top_p, jint seed) {
    if (!g_model) { return; }

    common_params_sampling sparams;
    sparams.temp  = temp;
    sparams.top_k = top_k;
    sparams.top_p = top_p;
    sparams.seed  = (uint32_t) seed;

    release_sampler();
    g_sampler = common_sampler_init(g_model, sparams);
    LOGi("%s: sampler rebuilt (temp=%.2f, top_k=%d, top_p=%.2f, seed=%d)",
         __func__, temp, top_k, top_p, seed);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_chatai_llama_LlamaEngineImpl_systemInfo(JNIEnv * env, jobject /*unused*/) {
    return env->NewStringUTF(llama_print_system_info());
}

static std::string get_backend() {
    std::vector<std::string> backends;
    for (size_t i = 0; i < ggml_backend_reg_count(); i++) {
        auto * reg = ggml_backend_reg_get(i);
        std::string name = ggml_backend_reg_name(reg);
        if (name != "CPU") {
            backends.push_back(name);
        }
    }
    return backends.empty() ? "CPU" : join(backends, ",");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_chatai_llama_LlamaEngineImpl_benchModel(
        JNIEnv * env, jobject /*unused*/, jint pp, jint tg, jint pl, jint nr) {
    if (!g_model) {
        return env->NewStringUTF("No model loaded");
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx           = (uint32_t) pp;
    ctx_params.n_batch         = BATCH_SIZE;
    ctx_params.n_ubatch        = BATCH_SIZE;
    ctx_params.n_threads       = 4;
    ctx_params.n_threads_batch = 4;

    auto * context = llama_init_from_model(g_model, ctx_params);
    if (!context) {
        return env->NewStringUTF("Failed to init context for bench");
    }

    double pp_avg = 0.0, tg_avg = 0.0;

    for (int nri = 0; nri < nr; nri++) {
        common_batch batch(context);

        for (int i = 0; i < pp; i++) {
            batch.add(0, i, 0, false);
        }
        batch.set_output(batch.size() - 1, true);
        llama_memory_clear(llama_get_memory(context), false);

        const auto t_pp_start = ggml_time_us();
        if (llama_process(context, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) {
            LOGe("decode failed during prompt processing");
        }
        const auto t_pp_end = ggml_time_us();

        llama_memory_clear(llama_get_memory(context), false);
        const auto t_tg_start = ggml_time_us();
        for (int i = 0; i < tg; i++) {
            batch.clear();
            for (int j = 0; j < pl; j++) {
                batch.add(0, i, j, true);
            }
            if (llama_process(context, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) {
                LOGe("decode failed during text generation");
            }
        }
        const auto t_tg_end = ggml_time_us();

        llama_memory_clear(llama_get_memory(context), false);

        const auto t_pp = double(t_pp_end - t_pp_start) / 1000000.0;
        const auto t_tg = double(t_tg_end - t_tg_start) / 1000000.0;

        pp_avg += double(pp) / t_pp;
        tg_avg += double(pl * tg) / t_tg;
    }

    llama_free(context);

    pp_avg /= double(nr);
    tg_avg /= double(nr);

    char model_desc[128];
    llama_model_desc(g_model, model_desc, sizeof(model_desc));

    const auto model_size     = double(llama_model_size(g_model)) / 1024.0 / 1024.0 / 1024.0;
    const auto model_n_params = double(llama_model_n_params(g_model)) / 1e9;

    std::stringstream result;
    result << std::setprecision(3);
    result << "| model | size | params | backend | test | t/s |\n";
    result << "| --- | --- | --- | --- | --- | --- |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | "
           << get_backend() << " | pp " << pp << " | " << pp_avg << " |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | "
           << get_backend() << " | tg " << tg << " | " << tg_avg << " |\n";
    return env->NewStringUTF(result.str().c_str());
}

constexpr const char * ROLE_SYSTEM    = "system";
constexpr const char * ROLE_USER      = "user";
constexpr const char * ROLE_ASSISTANT = "assistant";

static std::vector<common_chat_msg> chat_msgs;
static llama_pos system_prompt_position;
static llama_pos current_position;

static void reset_long_term_states(const bool clear_kv_cache = true) {
    chat_msgs.clear();
    system_prompt_position = 0;
    current_position       = 0;

    if (clear_kv_cache && g_context) {
        llama_memory_clear(llama_get_memory(g_context), false);
    }
}

/**
 * Returns {usedTokens, totalContext}. usedTokens covers everything held in the KV
 * cache (system prompt + replayed history + generated replies), so the UI can show
 * how much of the window is spent.
 */
extern "C"
JNIEXPORT jintArray JNICALL
Java_com_example_chatai_llama_LlamaEngineImpl_nativeContextUsage(JNIEnv * env, jobject /*unused*/) {
    jint out[2];
    out[0] = g_context ? (jint) current_position : 0;
    out[1] = (jint) g_n_ctx;

    jintArray result = env->NewIntArray(2);
    if (result) {
        env->SetIntArrayRegion(result, 0, 2, out);
    }
    return result;
}

// Drop the older half of the tokens that follow the system prompt, then renumber
// the surviving KV entries so they stay contiguous from system_prompt_position.
static void shift_context() {
    const int n_discard = (current_position - system_prompt_position) / 2;
    LOGi("%s: discarding %d tokens", __func__, n_discard);

    llama_memory_seq_rm(llama_get_memory(g_context), 0,
                        system_prompt_position,
                        system_prompt_position + n_discard);
    llama_memory_seq_add(llama_get_memory(g_context), 0,
                         system_prompt_position + n_discard,
                         current_position,
                         -n_discard);
    current_position -= n_discard;
    LOGi("%s: done, current position %d", __func__, current_position);
}

static std::string chat_add_and_format(const std::string & role, const std::string & content) {
    common_chat_msg new_msg;
    new_msg.role    = role;
    new_msg.content = content;

    auto formatted = common_chat_format_single(
            g_chat_templates.get(), chat_msgs, new_msg,
            role == ROLE_USER, /* use_jinja */ false);

    chat_msgs.push_back(new_msg);
    return formatted;
}

static llama_pos stop_generation_position;
static std::string cached_token_chars;
static std::ostringstream assistant_ss;

static void reset_short_term_states() {
    stop_generation_position = 0;
    cached_token_chars.clear();
    assistant_ss.str("");
    assistant_ss.clear();
}

static int decode_tokens_in_batches(
        llama_context * context,
        common_batch & batch,
        const llama_tokens & tokens,
        const llama_pos start_pos,
        const bool compute_last_logit = false) {
    LOGd("%s: decoding %d tokens from position %d", __func__, (int) tokens.size(), start_pos);

    for (int i = 0; i < (int) tokens.size(); i += BATCH_SIZE) {
        const int cur_batch_size = std::min((int) tokens.size() - i, BATCH_SIZE);
        batch.clear();

        if (start_pos + i + cur_batch_size >= g_n_ctx - OVERFLOW_HEADROOM) {
            LOGw("%s: batch will not fit, shifting context", __func__);
            shift_context();
        }

        for (int j = 0; j < cur_batch_size; j++) {
            const llama_token token_id = tokens[i + j];
            const llama_pos position   = start_pos + i + j;
            const bool want_logit      = compute_last_logit && (i + j == (int) tokens.size() - 1);
            batch.add(token_id, position, 0, want_logit);
        }

        if (llama_process(context, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) {
            LOGe("%s: decode failed", __func__);
            return 1;
        }
    }
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_example_chatai_llama_LlamaEngineImpl_processSystemPrompt(
        JNIEnv * env, jobject /*unused*/, jstring jsystem_prompt) {
    if (!g_context || !g_chat_templates) {
        LOGe("%s: context not ready", __func__);
        return 1;
    }

    // Resetting here is what makes repeated calls legal: every call starts a
    // brand new conversation with an empty KV cache.
    reset_long_term_states();
    reset_short_term_states();

    const auto * system_prompt = env->GetStringUTFChars(jsystem_prompt, nullptr);
    std::string formatted_system_prompt(system_prompt);

    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        formatted_system_prompt = chat_add_and_format(ROLE_SYSTEM, system_prompt);
    }
    env->ReleaseStringUTFChars(jsystem_prompt, system_prompt);

    const auto system_tokens = common_tokenize(
            g_context, formatted_system_prompt, has_chat_template, has_chat_template);

    const int max_batch_size = g_n_ctx - OVERFLOW_HEADROOM;
    if ((int) system_tokens.size() > max_batch_size) {
        LOGe("%s: system prompt too long (%d tokens, max %d)",
             __func__, (int) system_tokens.size(), max_batch_size);
        return 2;
    }

    if (decode_tokens_in_batches(g_context, g_batch, system_tokens, current_position)) {
        LOGe("%s: decode failed", __func__);
        return 3;
    }

    system_prompt_position = current_position = (int) system_tokens.size();
    LOGi("%s: system prompt applied (%d tokens)", __func__, (int) system_tokens.size());
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_example_chatai_llama_LlamaEngineImpl_processUserPrompt(
        JNIEnv * env, jobject /*unused*/, jstring juser_prompt, jint n_predict) {
    if (!g_context) {
        LOGe("%s: context not ready", __func__);
        return 1;
    }
    if (!g_sampler) {
        LOGe("%s: sampler not initialized", __func__);
        return 1;
    }

    reset_short_term_states();

    const auto * user_prompt = env->GetStringUTFChars(juser_prompt, nullptr);
    std::string formatted_user_prompt(user_prompt);

    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        formatted_user_prompt = chat_add_and_format(ROLE_USER, user_prompt);
    }
    env->ReleaseStringUTFChars(juser_prompt, user_prompt);

    auto user_tokens = common_tokenize(
            g_context, formatted_user_prompt, has_chat_template, has_chat_template);

    const int max_batch_size = g_n_ctx - OVERFLOW_HEADROOM;
    if ((int) user_tokens.size() > max_batch_size) {
        const int skipped = (int) user_tokens.size() - max_batch_size;
        user_tokens.resize(max_batch_size);
        LOGw("%s: user prompt truncated, skipped %d tokens", __func__, skipped);
    }

    if (decode_tokens_in_batches(g_context, g_batch, user_tokens, current_position, true)) {
        LOGe("%s: decode failed", __func__);
        return 2;
    }

    current_position += (int) user_tokens.size();

    // The upstream example adds the prompt size twice here, which cuts generation
    // short as the conversation grows. Position plus the requested budget is enough.
    stop_generation_position = current_position + n_predict;
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_example_chatai_llama_LlamaEngineImpl_replayTurn(
        JNIEnv * env, jobject /*unused*/, jstring jcontent, jboolean is_user) {
    if (!g_context || !g_chat_templates) {
        LOGe("%s: context not ready", __func__);
        return 1;
    }

    const auto * content = env->GetStringUTFChars(jcontent, nullptr);
    std::string formatted(content);
    env->ReleaseStringUTFChars(jcontent, content);

    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    const char * role = is_user ? ROLE_USER : ROLE_ASSISTANT;
    if (has_chat_template) {
        formatted = chat_add_and_format(role, formatted);
    } else {
        // No template: still record the turn so later formatting stays coherent.
        common_chat_msg msg;
        msg.role    = role;
        msg.content = formatted;
        chat_msgs.push_back(msg);
    }

    auto tokens = common_tokenize(g_context, formatted, has_chat_template, has_chat_template);
    const int max_batch_size = g_n_ctx - OVERFLOW_HEADROOM;
    if ((int) tokens.size() > max_batch_size) {
        tokens.resize(max_batch_size);
    }

    if (decode_tokens_in_batches(g_context, g_batch, tokens, current_position)) {
        LOGe("%s: replay decode failed", __func__);
        return 2;
    }
    current_position += (int) tokens.size();
    return 0;
}

static bool is_valid_utf8(const char * string) {
    if (!string) { return true; }

    const auto * bytes = (const unsigned char *) string;
    int num;

    while (*bytes != 0x00) {
        if ((*bytes & 0x80) == 0x00) {
            num = 1;
        } else if ((*bytes & 0xE0) == 0xC0) {
            num = 2;
        } else if ((*bytes & 0xF0) == 0xE0) {
            num = 3;
        } else if ((*bytes & 0xF8) == 0xF0) {
            num = 4;
        } else {
            return false;
        }

        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) {
                return false;
            }
            bytes += 1;
        }
    }
    return true;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_chatai_llama_LlamaEngineImpl_generateNextToken(
        JNIEnv * env, jobject /*unused*/) {
    if (!g_context || !g_sampler) { return nullptr; }

    if (current_position >= g_n_ctx - OVERFLOW_HEADROOM) {
        LOGw("%s: context full, shifting", __func__);
        shift_context();
    }

    if (current_position >= stop_generation_position) {
        LOGi("%s: stop position reached", __func__);
        return nullptr;
    }

    const auto new_token_id = common_sampler_sample(g_sampler, g_context, -1);
    common_sampler_accept(g_sampler, new_token_id, true);

    g_batch.clear();
    g_batch.add(new_token_id, current_position, 0, true);
    if (llama_process(g_context, LLAMA_PROCESS_TYPE_DECODE, g_batch.get()) != 0) {
        LOGe("%s: decode failed for generated token", __func__);
        return nullptr;
    }

    current_position++;

    if (llama_vocab_is_eog(llama_model_get_vocab(g_model), new_token_id)) {
        LOGi("%s: EOG reached", __func__);
        chat_add_and_format(ROLE_ASSISTANT, assistant_ss.str());
        return nullptr;
    }

    auto new_token_chars = common_token_to_piece(g_context, new_token_id);
    cached_token_chars += new_token_chars;

    // A multi-byte UTF-8 character can be split across tokens; hold the bytes
    // back until the buffer decodes cleanly.
    if (is_valid_utf8(cached_token_chars.c_str())) {
        const jstring result = env->NewStringUTF(cached_token_chars.c_str());
        assistant_ss << cached_token_chars;
        cached_token_chars.clear();
        return result;
    }

    return env->NewStringUTF("");
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_chatai_llama_LlamaEngineImpl_unload(JNIEnv * /*env*/, jobject /*unused*/) {
    if (g_context) {
        reset_long_term_states();
        reset_short_term_states();
    }

    release_sampler();
    g_chat_templates.reset();
    g_batch = common_batch();

    if (g_context) {
        llama_free(g_context);
        g_context = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    LOGi("%s: resources released", __func__);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_chatai_llama_LlamaEngineImpl_shutdown(JNIEnv * /*env*/, jobject /*unused*/) {
    llama_backend_free();
    LOGi("%s: backend freed", __func__);
}
