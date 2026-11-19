//! إطار واجهة RustPad (بلا اعتماديات): تصف الواجهة بـ Rust، والقشرة (Java) ترسمها وتعيد إليك الأحداث.
//! عادةً لا تحتاج إلى تعديل هذا الملف؛ عدّل lib.rs.
//!
//! العناصر: col, row, text, button, input, checkbox, list, list_sub, progress, spacer, image_bytes, image_file
//! التعديلات: .pad(dp) .gap(dp) .weight(w) .size(sp) .bold() .color("#RRGGBB") .bg("#RRGGBB") .center()
//!            .multiline() .number() .password() .w(dp) .h(dp) .on_click("id")
//! التنقل: Ui::back(true) يعترض زر الرجوع ويرسل حدث "back" (احتفظ بمكدس شاشات في حالتك).
//! التخزين: storage::get/set/remove (نصوص تُحفظ في مجلد التطبيق الخاص وتبقى بين مرات التشغيل).
use std::collections::HashMap;
use std::ffi::c_void;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::Mutex;

// ======================= الواجهة =======================

pub struct Node {
    kind: &'static str,
    fields: Vec<(&'static str, String)>,
    kids: Vec<Node>,
}

fn esc(s: &str) -> String {
    let mut o = String::with_capacity(s.len() + 2);
    o.push('"');
    for c in s.chars() {
        match c {
            '"' => o.push_str("\\\""),
            '\\' => o.push_str("\\\\"),
            '\n' => o.push_str("\\n"),
            '\r' => o.push_str("\\r"),
            '\t' => o.push_str("\\t"),
            c if (c as u32) < 0x20 => o.push_str(&format!("\\u{:04x}", c as u32)),
            c => o.push(c),
        }
    }
    o.push('"');
    o
}

fn num(v: f32) -> String {
    if v.is_finite() { format!("{}", v) } else { "0".to_string() }
}

impl Node {
    fn new(kind: &'static str) -> Node {
        Node { kind, fields: Vec::new(), kids: Vec::new() }
    }
    fn with(mut self, k: &'static str, raw: String) -> Node {
        self.fields.retain(|(kk, _)| *kk != k);
        self.fields.push((k, raw));
        self
    }
    pub fn pad(self, dp: u32) -> Node { self.with("pad", dp.to_string()) }
    pub fn gap(self, dp: u32) -> Node { self.with("gap", dp.to_string()) }
    pub fn weight(self, w: f32) -> Node { self.with("weight", num(w)) }
    pub fn size(self, sp: f32) -> Node { self.with("size", num(sp)) }
    pub fn bold(self) -> Node { self.with("bold", "true".to_string()) }
    pub fn color(self, hex: &str) -> Node { self.with("color", esc(hex)) }
    pub fn center(self) -> Node { self.with("align", esc("center")) }
    pub fn multiline(self) -> Node { self.with("multiline", "true".to_string()) }
    pub fn number(self) -> Node { self.with("kind", esc("number")) }
    pub fn password(self) -> Node { self.with("kind", esc("password")) }
    /// خلفية العنصر.
    pub fn bg(self, hex: &str) -> Node { self.with("bg", esc(hex)) }
    /// عرض/ارتفاع ثابت بوحدة dp (مفيد للصور).
    pub fn w(self, dp: u32) -> Node { self.with("w", dp.to_string()) }
    pub fn h(self, dp: u32) -> Node { self.with("h", dp.to_string()) }
    /// يجعل أي عنصر (نص، صورة، صف...) قابلاً للضغط ويرسل حدثاً بهذا المعرّف.
    pub fn on_click(self, id: &str) -> Node { self.with("click", esc(id)) }

    fn write(&self, out: &mut String) {
        out.push_str("{\"t\":");
        out.push_str(&esc(self.kind));
        for (k, v) in &self.fields {
            out.push(',');
            out.push_str(&esc(k));
            out.push(':');
            out.push_str(v);
        }
        if !self.kids.is_empty() {
            out.push_str(",\"children\":[");
            for (i, k) in self.kids.iter().enumerate() {
                if i > 0 { out.push(','); }
                k.write(out);
            }
            out.push(']');
        }
        out.push('}');
    }
}

pub fn col(children: Vec<Node>) -> Node { let mut n = Node::new("col"); n.kids = children; n }
pub fn row(children: Vec<Node>) -> Node { let mut n = Node::new("row"); n.kids = children; n }
pub fn text(s: impl AsRef<str>) -> Node { Node::new("text").with("text", esc(s.as_ref())) }
pub fn button(id: &str, label: &str) -> Node {
    Node::new("button").with("id", esc(id)).with("text", esc(label))
}
/// حقل إدخال: قيمته تصلك في كل حدث عبر `ev.input(id)`، وحدث "id" يُرسل عند الضغط على Done.
pub fn input(id: &str, value: &str, hint: &str) -> Node {
    Node::new("input").with("id", esc(id)).with("text", esc(value)).with("hint", esc(hint))
}
/// حدث بالمعرّف id وقيمته "true" أو "false".
pub fn checkbox(id: &str, label: &str, checked: bool) -> Node {
    Node::new("checkbox").with("id", esc(id)).with("text", esc(label)).with("checked", checked.to_string())
}
/// قائمة نصوص؛ الضغط على عنصر يرسل حدثاً بالمعرّف id وقيمته رقم العنصر (`ev.index()`).
pub fn list(id: &str, items: &[String]) -> Node {
    let arr: Vec<String> = items.iter().map(|s| esc(s)).collect();
    Node::new("list").with("id", esc(id)).with("items", format!("[{}]", arr.join(",")))
}
/// قائمة بعنوان ووصف لكل عنصر؛ الضغط يرسل رقم العنصر (`ev.index()`).
pub fn list_sub(id: &str, items: &[(String, String)]) -> Node {
    let a: Vec<String> = items.iter().map(|(t, _)| esc(t)).collect();
    let b: Vec<String> = items.iter().map(|(_, s)| esc(s)).collect();
    Node::new("list").with("id", esc(id))
        .with("items", format!("[{}]", a.join(",")))
        .with("subs", format!("[{}]", b.join(",")))
}
/// صورة من بايتات PNG/JPEG (مثلاً `include_bytes!("../res/logo.png")`). استخدم .w() و .h() للحجم.
pub fn image_bytes(data: &[u8]) -> Node { Node::new("image").with("b64", esc(&base64(data))) }
/// صورة من ملف على الجهاز (مثلاً داخل `storage::data_dir()`).
pub fn image_file(path: &str) -> Node { Node::new("image").with("path", esc(path)) }

fn base64(d: &[u8]) -> String {
    const T: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut o = String::with_capacity(d.len() / 3 * 4 + 4);
    for c in d.chunks(3) {
        let n = (c[0] as u32) << 16 | (*c.get(1).unwrap_or(&0) as u32) << 8 | *c.get(2).unwrap_or(&0) as u32;
        o.push(T[(n >> 18) as usize & 63] as char);
        o.push(T[(n >> 12) as usize & 63] as char);
        o.push(if c.len() > 1 { T[(n >> 6) as usize & 63] as char } else { '=' });
        o.push(if c.len() > 2 { T[n as usize & 63] as char } else { '=' });
    }
    o
}

/// من 0.0 إلى 1.0
pub fn progress(v: f32) -> Node { Node::new("progress").with("value", num(v.clamp(0.0, 1.0))) }
pub fn spacer(dp: u32) -> Node { Node::new("spacer").with("size", dp.to_string()) }

pub struct Ui {
    root: Node,
    title: Option<String>,
    toast: Option<String>,
    tick_ms: u32,
    back: bool,
}

impl Ui {
    pub fn new(root: Node) -> Ui { Ui { root, title: None, toast: None, tick_ms: 0, back: false } }
    /// true = زر الرجوع في النظام يرسل حدث "back" بدل إغلاق التطبيق (أعد false من event لإغلاقه).
    pub fn back(mut self, on: bool) -> Ui { self.back = on; self }
    pub fn title(mut self, t: impl Into<String>) -> Ui { self.title = Some(t.into()); self }
    /// رسالة قصيرة تظهر مرة واحدة مع هذا العرض.
    pub fn toast(mut self, t: impl Into<String>) -> Ui { self.toast = Some(t.into()); self }
    /// حدث "tick" كل ms مللي ثانية (0 = معطّل). مفيد للمؤقتات والتحديث الدوري.
    pub fn tick(mut self, ms: u32) -> Ui { self.tick_ms = ms; self }

    fn to_json(&self) -> String {
        let mut o = String::from("{");
        if let Some(t) = &self.title { o.push_str(&format!("\"title\":{},", esc(t))); }
        if let Some(t) = &self.toast { o.push_str(&format!("\"toast\":{},", esc(t))); }
        o.push_str(&format!("\"tick_ms\":{},\"back\":{},\"root\":", self.tick_ms, self.back));
        self.root.write(&mut o);
        o.push('}');
        o
    }
}

// ======================= التطبيق والأحداث =======================

pub struct Event {
    pub id: String,
    pub value: String,
    inputs: HashMap<String, String>,
}

impl Event {
    /// القيمة الحالية لحقل إدخال.
    pub fn input(&self, id: &str) -> Option<&str> { self.inputs.get(id).map(|s| s.as_str()) }
    /// رقم العنصر المضغوط في list.
    pub fn index(&self) -> usize { self.value.parse().unwrap_or(0) }
    /// حالة checkbox.
    pub fn checked(&self) -> bool { self.value == "true" }
}

pub trait App {
    /// يرسم الواجهة الحالية من حالة التطبيق.
    fn view(&mut self) -> Ui;
    /// يعالج حدثاً. أعد true لإعادة رسم الواجهة (view) أو false لتركها.
    fn event(&mut self, _ev: &Event) -> bool { false }
}

fn unescape(s: &str) -> String {
    let mut o = String::with_capacity(s.len());
    let mut it = s.chars();
    while let Some(c) = it.next() {
        if c == '\\' {
            match it.next() {
                Some('n') => o.push('\n'),
                Some('t') => o.push('\t'),
                Some('\\') => o.push('\\'),
                Some(x) => { o.push('\\'); o.push(x); }
                None => o.push('\\'),
            }
        } else { o.push(c); }
    }
    o
}

fn parse_inputs(raw: &str) -> HashMap<String, String> {
    let mut m = HashMap::new();
    for line in raw.split('\n') {
        if let Some((k, v)) = line.split_once('\t') { m.insert(unescape(k), unescape(v)); }
    }
    m
}

/// تخزين دائم بسيط (مفتاح/قيمة نصية) في مجلد التطبيق الخاص.
pub mod storage {
    use std::path::PathBuf;

    /// مجلد بيانات التطبيق الخاص (تضبطه القشرة عبر RUSTPAD_DATA).
    pub fn data_dir() -> PathBuf {
        PathBuf::from(std::env::var("RUSTPAD_DATA").unwrap_or_else(|_| ".".to_string()))
    }
    fn path(key: &str) -> PathBuf {
        let hex: String = key.bytes().map(|b| format!("{:02x}", b)).collect();
        data_dir().join("kv").join(hex)
    }
    pub fn get(key: &str) -> Option<String> { std::fs::read_to_string(path(key)).ok() }
    pub fn set(key: &str, value: &str) -> bool {
        let p = path(key);
        if let Some(d) = p.parent() { let _ = std::fs::create_dir_all(d); }
        let tmp = p.with_extension("tmp");
        std::fs::write(&tmp, value).is_ok() && std::fs::rename(&tmp, &p).is_ok()
    }
    pub fn remove(key: &str) { let _ = std::fs::remove_file(path(key)); }
}

static APP: Mutex<Option<Box<dyn App + Send>>> = Mutex::new(None);

fn error_ui(msg: &str) -> String {
    Ui::new(col(vec![text("Rust error").bold().size(20.0).color("#CC3333"), text(msg)]).pad(16).gap(8)).to_json()
}

fn guarded(f: impl FnOnce() -> String) -> String {
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(s) => s,
        Err(p) => {
            let msg = p.downcast_ref::<&str>().map(|s| s.to_string())
                .or_else(|| p.downcast_ref::<String>().cloned())
                .unwrap_or_else(|| "panic".to_string());
            error_ui(&format!("panic: {}", msg))
        }
    }
}

pub fn do_init() -> String {
    guarded(|| {
        let mut g = APP.lock().unwrap_or_else(|e| e.into_inner());
        let app = g.insert(crate::create_app());
        app.view().to_json()
    })
}

pub fn do_event(id: String, value: String, inputs: String) -> String {
    guarded(|| {
        let mut g = APP.lock().unwrap_or_else(|e| e.into_inner());
        match g.as_mut() {
            Some(app) => {
                let ev = Event { id, value, inputs: parse_inputs(&inputs) };
                if app.event(&ev) { app.view().to_json() } else { String::new() }
            }
            None => String::new(),
        }
    })
}

// ======================= جسر JNI (بلا اعتماديات) =======================
// JNIEnv* مؤشر إلى جدول دوال: 163 NewString، 164 GetStringLength، 165 GetStringChars، 166 ReleaseStringChars.
type JEnv = *mut *const *const c_void;
type JObject = *mut c_void;

unsafe fn get_string(env: JEnv, s: JObject) -> String {
    if s.is_null() { return String::new(); }
    let len: unsafe extern "system" fn(JEnv, JObject) -> i32 = std::mem::transmute(*(*env).add(164));
    let get: unsafe extern "system" fn(JEnv, JObject, *mut u8) -> *const u16 = std::mem::transmute(*(*env).add(165));
    let rel: unsafe extern "system" fn(JEnv, JObject, *const u16) = std::mem::transmute(*(*env).add(166));
    let n = len(env, s).max(0) as usize;
    let p = get(env, s, std::ptr::null_mut());
    if p.is_null() { return String::new(); }
    let out = String::from_utf16_lossy(std::slice::from_raw_parts(p, n));
    rel(env, s, p);
    out
}

unsafe fn new_string(env: JEnv, s: &str) -> JObject {
    let u: Vec<u16> = s.encode_utf16().collect();
    let f: unsafe extern "system" fn(JEnv, *const u16, i32) -> JObject = std::mem::transmute(*(*env).add(163));
    f(env, u.as_ptr(), u.len() as i32)
}

#[no_mangle]
pub extern "system" fn Java_dev_rustpad_shell_Native_init(env: JEnv, _class: JObject) -> JObject {
    let json = do_init();
    unsafe { new_string(env, &json) }
}

#[no_mangle]
pub extern "system" fn Java_dev_rustpad_shell_Native_event(
    env: JEnv, _class: JObject, id: JObject, value: JObject, inputs: JObject,
) -> JObject {
    let json = unsafe { do_event(get_string(env, id), get_string(env, value), get_string(env, inputs)) };
    unsafe { new_string(env, &json) }
}
