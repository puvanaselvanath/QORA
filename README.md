# QORA v0.3
Campus queue + menu + pickup ordering for VIT-AP. Node 18+, nothing to install.

    node server.js      ->  http://localhost:3000

| Page | URL | Who |
|---|---|---|
| Student app (installable) | / | students: set name, hostel (men/women), mess type (veg/non-veg/special) once |
| Admin | /admin.html | you: edit hostel mess menus (per hostel, mess type, day) and every outlet's menu (add, edit, price, sold out, delete). PIN in data/staff.json ("admin") |
| Outlet screen | /outlet.html | outlet staff: call next in queue, update pickup orders. PIN per outlet in data/staff.json |

No VTOP, no passwords, no email codes. A student is identified by a random id stored on their phone.
Delivery is not built.

## Before real students use it
1. Change every PIN in data/staff.json (all sample PINs are 1111, admin is admin123).
2. Host it on the internet with HTTPS so students can open and install it on their phones (see chat for options).
3. data/db.json holds everything (users, queues, orders, menus). Back it up. Fine for a pilot; move to PostgreSQL later.
4. Background push notifications for meal reminders need web push (next step). Reminders currently work while the app is open.
