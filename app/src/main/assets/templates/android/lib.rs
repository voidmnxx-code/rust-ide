// تطبيقك: عدّل الحالة (struct) و view() و event(). الإطار في ui.rs.
// يوضح: عدة شاشات مع زر الرجوع، صورة، حفظ دائم، قائمة بعنوان ووصف، ومؤقت.
mod ui;
use ui::*;

pub fn create_app() -> Box<dyn App + Send> {
    Box::new(Demo::load())
}

#[derive(Clone, Copy, PartialEq)]
enum Screen { Home, Todos, About, Device }

struct Demo {
    stack: Vec<Screen>, // مكدس الشاشات؛ الأخيرة هي الظاهرة
    name: String,
    count: i32,
    todos: Vec<String>,
    toast: Option<String>,
    seconds: u32,
    timer: bool,
    want: Option<&'static str>, // أمر جهاز ينتظر التنفيذ في العرض التالي
    info: String,
    photo: Option<String>,
}

impl Demo {
    fn load() -> Demo {
        Demo {
            stack: vec![Screen::Home],
            name: storage::get("name").unwrap_or_default(),
            count: storage::get("count").and_then(|s| s.parse().ok()).unwrap_or(0),
            todos: storage::get("todos")
                .map(|s| s.lines().map(|l| l.to_string()).collect())
                .unwrap_or_default(),
            toast: None,
            seconds: 0,
            timer: false,
            want: None,
            info: String::from("Tap a button"),
            photo: None,
        }
    }

    fn save(&self) {
        storage::set("name", &self.name);
        storage::set("count", &self.count.to_string());
        storage::set("todos", &self.todos.join("\n"));
    }

    fn screen(&self) -> Screen { *self.stack.last().unwrap_or(&Screen::Home) }

    fn home(&self) -> Node {
        let greeting = if self.name.is_empty() { "Hello from Rust!".to_string() } else { format!("Hello, {}!", self.name) };
        col(vec![
            image_bytes(include_bytes!("../res/logo.png")).w(96).h(96),
            text(greeting).size(24.0).bold().center(),
            input("name", &self.name, "Your name"),
            row(vec![
                button("dec", "-"),
                text(self.count.to_string()).size(28.0).weight(1.0).center(),
                button("inc", "+"),
            ]).gap(12),
            progress((self.count.rem_euclid(11)) as f32 / 10.0),
            row(vec![
                button("timer", if self.timer { "Stop timer" } else { "Start timer" }),
                text(format!("{} s", self.seconds)).weight(1.0).center(),
            ]).gap(12),
            row(vec![button("go_todos", "Todos").weight(1.0), button("go_about", "About").weight(1.0), button("go_device", "Device").weight(1.0)]).gap(12),
            text("Values are saved automatically and survive restarts.").size(12.0).color("#888888"),
        ])
        .pad(16).gap(10)
    }

    fn todos_screen(&self) -> Node {
        let items: Vec<(String, String)> = self.todos.iter().enumerate()
            .map(|(i, t)| (t.clone(), format!("item #{} - tap to remove", i + 1))).collect();
        col(vec![
            row(vec![input("todo", "", "New todo").weight(1.0), button("add", "Add")]).gap(8),
            if items.is_empty() { text("Nothing yet.").color("#888888") } else { list_sub("todos", &items) },
        ])
        .pad(16).gap(10)
    }

    // يحتاج أذونات في App settings: VIBRATE, ACCESS_FINE_LOCATION, CAMERA, POST_NOTIFICATIONS ...
    fn device_screen(&self) -> Node {
        let mut kids = vec![
            text(self.info.clone()).size(14.0),
            row(vec![button("d_vibrate", "Vibrate").weight(1.0), button("d_notify", "Notify").weight(1.0)]).gap(8),
            row(vec![button("d_loc", "Location").weight(1.0), button("d_net", "Network").weight(1.0)]).gap(8),
            row(vec![button("d_cam", "Camera").weight(1.0), button("d_pick", "Pick image").weight(1.0)]).gap(8),
            row(vec![button("d_acc", "Accelerometer").weight(1.0), button("d_copy", "Copy+Share").weight(1.0)]).gap(8),
        ];
        if let Some(p) = &self.photo { kids.push(image_file(p).h(220)); }
        col(kids).pad(16).gap(10)
    }

    fn about_screen(&self) -> Node {
        col(vec![
            image_bytes(include_bytes!("../res/logo.png")).w(64).h(64).on_click("logo"),
            text("Built with RustPad").bold().size(20.0),
            text("UI is described in Rust (view) and drawn by a tiny Java shell. Tap the logo."),
            text(format!("Data dir: {}", storage::data_dir().display())).size(11.0).color("#888888"),
        ])
        .pad(16).gap(10)
    }
}

impl App for Demo {
    fn view(&mut self) -> Ui {
        let (title, body) = match self.screen() {
            Screen::Home => ("My Rust App", self.home()),
            Screen::Todos => ("Todos", self.todos_screen()),
            Screen::About => ("About", self.about_screen()),
            Screen::Device => ("Device", self.device_screen()),
        };
        let mut ui = Ui::new(body).title(title).back(self.stack.len() > 1);
        if self.timer && self.screen() == Screen::Home { ui = ui.tick(1000); }
        if let Some(t) = self.toast.take() { ui = ui.toast(t); }
        match self.want.take() {
            Some("vibrate") => ui = ui.vibrate(200),
            Some("notify") => ui = ui.notify("Hello", "Notification from Rust"),
            Some("location") => ui = ui.location(),
            Some("network") => ui = ui.network(),
            Some("camera") => ui = ui.camera(),
            Some("pick") => ui = ui.pick_image(),
            Some("accel") => ui = ui.sensor("accel"),
            Some("copy") => ui = ui.copy("Hello from Rust").share("Hello from Rust"),
            _ => {}
        }
        ui
    }

    fn event(&mut self, ev: &Event) -> bool {
        if let Some(n) = ev.input("name") { self.name = n.to_string(); }
        let redraw = match ev.id.as_str() {
            "inc" => { self.count += 1; true }
            "dec" => { self.count -= 1; true }
            "timer" => { self.timer = !self.timer; true }
            "tick" => { self.seconds += 1; true }
            "go_todos" => { self.stack.push(Screen::Todos); true }
            "go_about" => { self.stack.push(Screen::About); true }
            "go_device" => { self.stack.push(Screen::Device); true }
            "d_vibrate" => { self.want = Some("vibrate"); true }
            "d_notify" => { self.want = Some("notify"); true }
            "d_loc" => { self.want = Some("location"); self.info = "Locating...".into(); true }
            "d_net" => { self.want = Some("network"); true }
            "d_cam" => { self.want = Some("camera"); true }
            "d_pick" => { self.want = Some("pick"); true }
            "d_acc" => { self.want = Some("accel"); true }
            "d_copy" => { self.want = Some("copy"); true }
            "location" => { self.info = format!("Location (lat,lon,accuracy): {}", ev.value); true }
            "network" => { self.info = format!("Network: {}", ev.value); true }
            "sensor" => { self.info = format!("Sensor {}", ev.value); true }
            "photo" | "picked" => { self.photo = Some(ev.value.clone()); self.info = "Image received".into(); true }
            "permission" => { self.info = format!("Permission {}", ev.value); true }
            "error" => { self.info = format!("Error: {}", ev.value); true }
            "back" => { if self.stack.len() > 1 { self.stack.pop(); true } else { false } }
            "logo" => { self.toast = Some("Rust + Android".into()); true }
            "add" => {
                let t = ev.input("todo").unwrap_or("").trim().to_string();
                if t.is_empty() { self.toast = Some("Type something first".into()); } else { self.todos.push(t); }
                true
            }
            "todos" => {
                let i = ev.index();
                if i < self.todos.len() { let t = self.todos.remove(i); self.toast = Some(format!("Removed: {}", t)); }
                true
            }
            "name" => false,
            _ => false,
        };
        if redraw { self.save(); }
        redraw
    }
}
