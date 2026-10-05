// Clicks carry no client predictions. Assertions use server inventory snapshots.
const path = require('path');
const fs = require('fs');
const assert = require('assert/strict');
const mineflayer = require(path.join(process.argv[2], 'mineflayer'));
const bot = mineflayer.createBot({host: '127.0.0.1', port: Number(process.argv[3]), username: 'GuiVerifier', version: '1.21.11'});
const messages = [];
const results = [];
let stateId = 0;
let fatal;
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
bot.on('messagestr', text => messages.push(text));
bot.on('error', error => { fatal = error; });
bot.on('kicked', reason => { fatal = new Error(JSON.stringify(reason)); });
for (const packet of ['window_items', 'set_slot']) bot._client.on(packet, data => {
  if (data.stateId !== undefined) stateId = data.stateId;
});

async function until(check, description, timeout = 10000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) {
    if (fatal) throw fatal;
    const result = check();
    if (result) return result;
    await delay(30);
  }
  throw new Error('Timed out: ' + description + '\n' + messages.slice(-15).join('\n'));
}
async function command(text, prefix) {
  const start = messages.length;
  bot.chat('/guiprobe ' + text);
  return until(() => messages.slice(start).find(line => line.startsWith(prefix)), text);
}
function packet(slot, mouseButton, mode) {
  assert(bot.currentWindow, 'a GUI must be open');
  bot._client.write('window_click', {
    windowId: bot.currentWindow.id, stateId, slot, mouseButton, mode,
    changedSlots: [], cursorItem: null
  });
}
async function inspect() {
  const line = await command('inspect', 'GUI_STATE ');
  const [, holder, icon, second, cursor, stored, dropped, hotbar, offhand] = line.split(' ');
  return {holder, icon, second, cursor, stored: Number(stored), dropped: Number(dropped), hotbar, offhand};
}
async function prepare(kind, seed = '') {
  const previous = bot.currentWindow?.id;
  await command('prepare ' + kind, 'GUI_READY ');
  await until(() => bot.currentWindow && bot.currentWindow.id !== previous, 'new ' + kind + ' window');
  if (seed) await command('seed ' + seed, 'GUI_SEEDED ');
  const baseline = await inspect();
  assert.deepEqual(baseline, {holder: kind === 'external' ? 'external' : 'menu',
    icon: 'DIAMOND:8', second: 'AIR:0', cursor: seed === 'cursor' ? 'DIAMOND:8' : 'AIR:0',
    stored: seed === 'bottom' ? 8 : 0, dropped: 0, hotbar: 'STONE:4', offhand: 'STICK:1'}, 'prepared server baseline');
  console.log('BASELINE ' + kind + ' ' + seed + ' ' + JSON.stringify(baseline));
  return messages.length;
}
function isolated(start, callbacks) {
  const fired = messages.slice(start);
  assert(!fired.some(line => line.startsWith('GUI_GENERIC_')), fired.join('\n'));
  assert.equal(fired.filter(line => line === 'GUI_CALLBACK').length, callbacks, 'callback count');
}
function record(name, state) {
  results.push({name, state});
  console.log('PASS ' + name + ' ' + JSON.stringify(state));
}
async function main() {
  await until(() => bot.entity, 'spawn', 45000);
  await delay(800);
  for (const [name, button, mode, seed] of [
    ['LEFT', 0, 0, ''], ['RIGHT', 1, 0, ''], ['SHIFT_LEFT', 0, 1, ''],
    ['SHIFT_RIGHT', 1, 1, ''], ['NUMBER_KEY', 2, 2, ''],
    ['DOUBLE_CLICK', 0, 6, 'cursor'], ['DROP', 0, 4, ''],
    ['CONTROL_DROP', 1, 4, ''], ['SWAP_OFFHAND', 40, 2, '']
  ]) {
    const start = await prepare('locked', seed);
    packet(0, button, mode);
    await until(() => messages.slice(start).includes('GUI_EVENT ' + name + ' true'), name);
    const state = await inspect();
    assert.equal(state.icon, 'DIAMOND:8');
    assert.equal(state.cursor, seed === 'cursor' ? 'DIAMOND:8' : 'AIR:0');
    assert.equal(state.stored + state.dropped, 0);
    assert.equal(state.hotbar, 'STONE:4');
    assert.equal(state.offhand, 'STICK:1');
    isolated(start, 1);
    record('locked ' + name, state);
  }
  for (const [name, button, mode, seed] of [['SHIFT_LEFT', 0, 1, 'bottom'], ['SHIFT_RIGHT', 1, 1, 'bottom'], ['DOUBLE_CLICK', 0, 6, 'cursor']]) {
    const start = await prepare('locked', seed);
    packet(9, button, mode);
    await until(() => messages.slice(start).includes('GUI_EVENT ' + name + ' true'), 'bottom ' + name);
    const state = await inspect();
    assert.equal(state.icon, 'DIAMOND:8');
    assert.equal(state.stored, seed === 'bottom' ? 8 : 0);
    isolated(start, 0);
    record('locked bottom ' + name, state);
  }
  for (const taking of [false, true]) {
    for (const [slot, name] of [[9, 'bottom'], [-999, 'outside']]) {
      const start = await prepare(taking ? 'taking' : 'locked', 'cursor');
      packet(slot, 0, 0);
      await until(() => messages.slice(start).some(line => line.startsWith('GUI_EVENT ') && line.endsWith(' false')), name);
      const state = await inspect();
      assert.equal(state.icon, 'DIAMOND:8');
      assert.equal(state.cursor, 'AIR:0');
      assert.equal(state.stored, slot === 9 ? 8 : 0);
      assert.equal(state.dropped, slot === -999 ? 8 : 0);
      isolated(start, 0);
      record((taking ? 'taking ' : 'locked ') + name + ' local movement', state);
    }
    const start = await prepare(taking ? 'taking' : 'locked', 'cursor');
    packet(-999, 0, 5); packet(9, 1, 5); packet(10, 1, 5); packet(-999, 2, 5);
    await until(() => messages.slice(start).includes('GUI_DRAG EVEN false'), 'bottom-only drag');
    const state = await inspect();
    assert.equal(state.icon, 'DIAMOND:8');
    assert.equal(state.second, 'AIR:0');
    assert.equal(state.cursor, 'AIR:0');
    assert.equal(state.stored, 8);
    isolated(start, 0);
    record((taking ? 'taking ' : 'locked ') + 'bottom-only drag', state);
  }
  for (const [name, button, mode, seed, icon, cursor, stored, dropped] of [
    ['RIGHT', 1, 0, '', 'DIAMOND:4', 'DIAMOND:4', 0, 0],
    ['SHIFT_LEFT', 0, 1, '', 'AIR:0', 'AIR:0', 8, 0],
    ['SHIFT_RIGHT', 1, 1, '', 'AIR:0', 'AIR:0', 8, 0],
    ['NUMBER_KEY', 2, 2, '', 'STONE:4', 'AIR:0', 8, 0],
    // Vanilla PICKUP_ALL needs an empty clicked slot to collect matching stacks.
    ['DOUBLE_CLICK', 0, 6, 'cursor', 'DIAMOND:8', 'DIAMOND:8', 0, 0],
    ['DROP', 0, 4, '', 'DIAMOND:7', 'AIR:0', 0, 1],
    ['CONTROL_DROP', 1, 4, '', 'AIR:0', 'AIR:0', 0, 8],
    ['SWAP_OFFHAND', 40, 2, '', 'STICK:1', 'AIR:0', 8, 0]
  ]) {
    const start = await prepare('taking', seed);
    packet(0, button, mode);
    await until(() => messages.slice(start).includes('GUI_EVENT ' + name + ' false'), 'taking ' + name);
    const state = await inspect();
    assert.equal(state.icon, icon); assert.equal(state.cursor, cursor);
    assert.equal(state.stored, stored); assert.equal(state.dropped, dropped);
    if (name === 'NUMBER_KEY') assert.equal(state.hotbar, 'DIAMOND:8');
    if (name === 'SWAP_OFFHAND') assert.equal(state.offhand, 'DIAMOND:8');
    isolated(start, 1);
    record('taking ' + name, state);
  }
  {
    const start = await prepare('taking', 'cursor');
    packet(1, 0, 6);
    await until(() => messages.slice(start).includes('GUI_EVENT DOUBLE_CLICK false'), 'taking collect via empty slot');
    const state = await inspect();
    assert.equal(state.icon, 'AIR:0'); assert.equal(state.cursor, 'DIAMOND:16');
    assert.equal(state.stored + state.dropped, 0);
    isolated(start, 0);
    record('taking DOUBLE_CLICK collects via empty slot', state);
  }
  for (const taking of [false, true]) for (const right of [false, true]) {
    const start = await prepare(taking ? 'taking' : 'locked', 'cursor');
    const button = right ? 4 : 0;
    packet(-999, button, 5);
    packet(1, button + 1, 5);
    packet(10, button + 1, 5);
    packet(-999, button + 2, 5);
    await until(() => messages.slice(start).includes('GUI_DRAG ' + (right ? 'SINGLE' : 'EVEN') + ' ' + !taking), 'drag');
    const state = await inspect();
    assert.equal(state.icon, 'DIAMOND:8');
    assert.equal(state.second, taking ? (right ? 'DIAMOND:1' : 'DIAMOND:4') : 'AIR:0');
    assert.equal(state.cursor, taking ? (right ? 'DIAMOND:6' : 'AIR:0') : 'DIAMOND:8');
    assert.equal(state.stored, taking ? (right ? 1 : 4) : 0);
    isolated(start, 0);
    record((taking ? 'taking' : 'locked') + ' drag ' + (right ? 'right' : 'left'), state);
  }
  for (const kind of ['taking', 'override', 'external']) {
    const start = await prepare(kind);
    packet(0, 0, 0);
    await until(() => messages.slice(start).includes('GUI_EVENT LEFT false'), kind);
    const state = await inspect();
    assert.equal(state.icon, 'AIR:0');
    assert.equal(state.cursor, 'DIAMOND:8');
    if (kind === 'external') assert.equal(messages.slice(start).filter(line => line.startsWith('GUI_GENERIC_CLICK')).length, 6);
    else isolated(start, 1);
    record(kind + ' extraction allowed', state);
  }
  {
    const start = await prepare('external', 'cursor');
    packet(-999, 0, 5); packet(1, 1, 5); packet(10, 1, 5); packet(-999, 2, 5);
    await until(() => messages.slice(start).includes('GUI_DRAG EVEN false'), 'external drag uncancel');
    const state = await inspect();
    assert.equal(state.second, 'DIAMOND:4'); assert.equal(state.stored, 4); assert.equal(state.cursor, 'AIR:0');
    assert.equal(messages.slice(start).filter(line => line.startsWith('GUI_GENERIC_DRAG')).length, 6);
    record('external drag uncancel preserved', state);
  }
  {
    const start = await prepare('locked');
    const retiredWindow = bot.currentWindow.id;
    bot.chat('/tys reload probe');
    await until(() => messages.slice(start).some(line => line.startsWith('Reloaded ')), 'targeted reload');
    await until(() => !bot.currentWindow, 'retired menu closes');
    bot._client.write('window_click', {windowId: retiredWindow, stateId, slot: 0, mouseButton: 0, mode: 0, changedSlots: [], cursorItem: null});
    await delay(150);
    isolated(start, 0);
    const next = await prepare('locked');
    packet(0, 0, 0);
    await until(() => messages.slice(next).includes('GUI_EVENT LEFT true'), 'reloaded menu');
    const state = await inspect();
    assert.equal(state.icon, 'DIAMOND:8'); assert.equal(state.cursor, 'AIR:0');
    isolated(next, 1);
    record('reload closes old menu and new callback runs once', state);
  }
  fs.writeFileSync(process.argv[4], JSON.stringify({passed: results.length, results}, null, 2));
}
main().then(() => { bot.quit(); setTimeout(() => process.exit(0), 500); }).catch(error => {
  console.error(error.stack);
  fs.writeFileSync(process.argv[4], JSON.stringify({passed: results.length, error: String(error), results, messages: messages.slice(-50)}, null, 2));
  bot.quit(); setTimeout(() => process.exit(1), 500);
});
