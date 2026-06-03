// JNI bridge over the HuggingFace `tokenizers` Rust crate — the SAME engine transformers'
// fast tokenizers wrap, so output is byte-exact with Python GemmaTokenizerFast.
// Symbols match Kotlin: com.example.clipcc.engine.HfTokenizer (companion @JvmStatic externs).
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jlong, jstring};
use jni::JNIEnv;
use tokenizers::Tokenizer;

#[no_mangle]
pub extern "system" fn Java_com_example_clipcc_engine_HfTokenizer_createTokenizer(
    mut env: JNIEnv,
    _class: JClass,
    bytes: JByteArray,
) -> jlong {
    let buf = env.convert_byte_array(&bytes).expect("convert tokenizer.json bytes");
    let tk = Tokenizer::from_bytes(&buf).expect("parse tokenizer.json");
    Box::into_raw(Box::new(tk)) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_example_clipcc_engine_HfTokenizer_tokenize(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    text: JString,
) -> jstring {
    let tk = unsafe { &*(ptr as *const Tokenizer) };
    let s: String = env.get_string(&text).expect("get text string").into();
    // add_special_tokens=true; the tokenizer.json normalizer is the sole source of truth.
    // SigLIP2 is case-sensitive — do NOT lowercase here or in Kotlin.
    let enc = tk.encode(s, true).expect("encode");
    let ids: Vec<i64> = enc.get_ids().iter().map(|&x| x as i64).collect();
    let out = serde_json::to_string(&ids).expect("serialize ids");
    env.new_string(out).expect("new_string").into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_example_clipcc_engine_HfTokenizer_deleteTokenizer(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) {
    if ptr != 0 {
        unsafe { drop(Box::from_raw(ptr as *mut Tokenizer)) };
    }
}
