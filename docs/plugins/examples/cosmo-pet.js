// Cosmo pet: Nova, a little moon that lives on your Wallet tab. Feed it, play with it and let it
// nap. It gets hungry, bored and sleepy as time passes (even while the app is closed), grows
// through the moon's phases as the days go by, and cheers up whenever you receive a payment.
//
// Everything is saved with cosmo.storage, so Nova remembers you between launches.

var HOUR = 3600000;
var DAY = 24 * HOUR;
// How many points each need loses per hour.
var DECAY = { food: 6, fun: 4, energy: 3 };

var pet = load();

function load() {
  var saved = cosmo.storage.get('pet');
  if (saved) return JSON.parse(saved);
  var now = Date.now();
  return { name: 'Nova', food: 80, fun: 80, energy: 80, born: now, at: now, lastPayment: null, mood: null, moodUntil: 0 };
}

function save() {
  cosmo.storage.set('pet', JSON.stringify(pet));
}

function clamp(value) {
  return Math.max(0, Math.min(100, Math.round(value)));
}

// Catches the needs up with the time that passed since Nova was last looked at.
function tick(now) {
  var hours = Math.max(0, now - pet.at) / HOUR;
  pet.food = clamp(pet.food - DECAY.food * hours);
  pet.fun = clamp(pet.fun - DECAY.fun * hours);
  pet.energy = clamp(pet.energy - DECAY.energy * hours);
  pet.at = now;
}

// A short-lived reaction shown instead of the usual face, e.g. right after eating.
function react(mood) {
  pet.mood = mood;
  pet.moodUntil = Date.now() + 60000;
}

// New incoming payments since last time make Nova happy. The first look only remembers the latest.
function notice(payments) {
  var incoming = payments.filter(function (p) { return p.incoming; });
  if (incoming.length === 0) return;
  var newest = incoming[0].date;
  if (pet.lastPayment !== null && newest > pet.lastPayment) {
    var fresh = incoming.filter(function (p) { return p.date > pet.lastPayment; });
    pet.fun = clamp(pet.fun + 15 * fresh.length);
    react('🤩');
    cosmo.toast(pet.name + ' saw you receive ' + fresh[0].amount + ' ' + fresh[0].asset + '!');
  }
  pet.lastPayment = newest;
}

// The moon's phases, by age in days.
function stage(now) {
  var days = (now - pet.born) / DAY;
  if (days < 1) return { icon: '🌑', name: 'New moon' };
  if (days < 3) return { icon: '🌒', name: 'Crescent' };
  if (days < 5) return { icon: '🌓', name: 'Half moon' };
  if (days < 7) return { icon: '🌔', name: 'Gibbous' };
  return { icon: '🌕', name: 'Full moon' };
}

function face(now) {
  if (pet.mood && now < pet.moodUntil) return pet.mood;
  var lowest = Math.min(pet.food, pet.fun, pet.energy);
  if (lowest === 0) return '😵';
  if (pet.energy < 20) return '😴';
  if (pet.food < 25) return '😟';
  if (pet.fun < 25) return '😐';
  if (lowest > 70) return '😄';
  return '🙂';
}

function status() {
  if (Math.min(pet.food, pet.fun, pet.energy) === 0) return pet.name + ' is sulking. Look after it!';
  if (pet.food < 25) return pet.name + ' is hungry.';
  if (pet.energy < 20) return pet.name + ' can barely keep its eyes open.';
  if (pet.fun < 25) return pet.name + ' is bored.';
  return pet.name + ' is doing great.';
}

function need(label, value) {
  return ui.column(
    ui.text(label + ' · ' + value + '%', { style: 'caption', color: value < 25 ? '#C62828' : undefined }),
    ui.progress(value / 100)
  );
}

function act(update) {
  tick(Date.now());
  update();
  save();
}

cosmo.registerWidget({
  id: 'pet',
  title: 'Cosmo pet',
  render: function (data) {
    var now = Date.now();
    tick(now);
    notice(data.payments);
    save();

    var phase = stage(now);
    var days = Math.floor((now - pet.born) / DAY);
    return ui.column(
      ui.row(
        ui.text(phase.icon + ' ' + face(now), { style: 'headline' }),
        ui.column(
          ui.title(pet.name),
          ui.text(phase.name + ' · ' + days + (days === 1 ? ' day' : ' days') + ' old', { style: 'caption' })
        )
      ),
      ui.text(status(), { style: 'label' }),
      need('Food', pet.food),
      need('Fun', pet.fun),
      need('Energy', pet.energy),
      ui.row(
        ui.button('Feed', function () {
          act(function () {
            if (pet.food >= 95) { react('🙅'); cosmo.toast(pet.name + ' is full.'); return; }
            pet.food = clamp(pet.food + 30);
            react('😋');
          });
        }),
        ui.button('Play', function () {
          act(function () {
            if (pet.energy < 15) { react('🥱'); cosmo.toast(pet.name + ' is too tired to play.'); return; }
            pet.fun = clamp(pet.fun + 25);
            pet.energy = clamp(pet.energy - 10);
            pet.food = clamp(pet.food - 5);
            react('🥳');
          });
        }),
        ui.button('Nap', function () {
          act(function () {
            pet.energy = clamp(pet.energy + 40);
            pet.fun = clamp(pet.fun - 5);
            react('💤');
          });
        })
      )
    );
  }
});
