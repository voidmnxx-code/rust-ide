// تطبيقك: عدّل الحالة (struct) و view() و event(). الإطار في ui.rs.
mod ui;
use ui::*;

pub fn create_app() -> Box<dyn App + Send> {
    Box::new(Demo::default())
}

#[derive(Default)]
struct Demo {
    count: i32,
    name: String,
    loud: bool,
    todos: Vec<String>,
    toast: Option<String>,
    seconds: u32,
    timer: bool,
}

impl App for Demo {
    fn view(&mut self) -> Ui {
        let mut greeting = if self.name.is_empty() {
            "Hello from Rust!".to_string()
        } else {
            format!("Hello, {}!", self.name)
        };
        if self.loud { greeting = greeting.to_uppercase(); }

        let mut ui = Ui::new(
            col(vec![
                text(greeting).size(24.0).bold().center(),
                input("name", &self.name, "Your name"),
                row(vec![
                    button("dec", "-"),
                    text(self.count.to_string()).size(28.0).weight(1.0).center(),
                    button("inc", "+"),
                ]).gap(12),
                checkbox("loud", "Loud mode", self.loud),
                progress((self.count.rem_euclid(11)) as f32 / 10.0),
                spacer(8),
                row(vec![
                    button("timer", if self.timer { "Stop timer" } else { "Start timer" }),
                    text(format!("{} s", self.seconds)).weight(1.0).center(),
                ]).gap(12),
                spacer(8),
                text("Todos (tap an item to remove it)").bold(),
                list("todos", &self.todos),
                button("add", "Add todo from name"),
            ])
            .pad(16)
            .gap(10),
        )
        .title("My Rust App");
        if self.timer { ui = ui.tick(1000); }
        if let Some(t) = self.toast.take() { ui = ui.toast(t); }
        ui
    }

    fn event(&mut self, ev: &Event) -> bool {
        // نحافظ على ما كُتب في الحقل قبل أي إعادة رسم
        if let Some(n) = ev.input("name") { self.name = n.to_string(); }
        match ev.id.as_str() {
            "inc" => self.count += 1,
            "dec" => self.count -= 1,
            "loud" => self.loud = ev.checked(),
            "timer" => self.timer = !self.timer,
            "tick" => { self.seconds += 1; }
            "add" => {
                if self.name.trim().is_empty() {
                    self.toast = Some("Type something in the name field first".into());
                } else {
                    self.todos.push(self.name.trim().to_string());
                    self.name.clear();
                }
            }
            "todos" => {
                let i = ev.index();
                if i < self.todos.len() {
                    let t = self.todos.remove(i);
                    self.toast = Some(format!("Removed: {}", t));
                }
            }
            "name" => {} // Done على لوحة المفاتيح
            _ => return false,
        }
        true
    }
}
