'use strict';
const assert = require('node:assert/strict');
const {parseGenerated, GENERATED_PROMPT} = require('./generated.js');
const example = GENERATED_PROMPT.split('Пример:\n')[1].split('\n\nОписание')[0];
const bot = parseGenerated(example);
assert.equal(bot.format, 'botcontrol');
assert.equal(bot.scenarios[0].trigger.value, '/start');
assert.deepEqual(parseGenerated('```json\n' + example + '\n```'), bot);
assert.throws(() => parseGenerated('Текст до JSON\n' + example), /JSON/);
assert.throws(() => parseGenerated(example.replace('"version":1', '"version":1,"token":"secret"')), /Неподдерживаемые поля/);
assert.throws(() => parseGenerated(example.replace('"name":"Помощь","text"', '"name":"Помощь","script":"alert(1)","text"')), /Неподдерживаемые поля/);
assert.throws(() => parseGenerated('x'.repeat(900001)), /слишком большой/);

// Stage 5 parity fields allowed cleanly
const parityBot = parseGenerated(JSON.stringify({
  format: 'botcontrol',
  version: 1,
  name: 'Parity',
  scenarios: [
    {
      id: 's1',
      name: 'Заказ',
      text: '<b>Привет, {user}!</b>',
      parseMode: 'HTML',
      trigger: { type: 'regex', value: '^заказ\\s+\\d+$' },
      inlineButtons: [{ id: 'b1', label: 'Детали', action: 'text', text: 'Ок', toast: 'Done' }],
    },
    { id: 's2', name: 'Кубик', text: '', diceEmoji: '🎯' },
    { id: 's3', name: 'Фото', text: 'Подпись', media: { type: 'photo', ref: 'https://example.com/a.jpg' } },
  ],
  buttons: [{ id: 'btn1', label: 'Кубик', scenarioId: 's2' }],
  schedule: [],
}));
assert.equal(parityBot.scenarios[0].trigger.type, 'regex');
assert.equal(parityBot.scenarios[1].diceEmoji, '🎯');
assert.equal(parityBot.scenarios[2].media.type, 'photo');
console.log('Generated import tests passed');
