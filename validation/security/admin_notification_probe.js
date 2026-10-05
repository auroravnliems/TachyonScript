'use strict'
// Local clients verify permission-scoped delivery. The module path is supplied by the test runner.
const fs = require('fs')
const mineflayer = require(process.argv[2])
const port = Number(process.argv[3])
const output = process.argv[4]
const result = { ready: [], messages: { TSAlert: [], TSObserver: [] }, errors: [] }
const bots = []
function persist() { fs.writeFileSync(output, JSON.stringify(result, null, 2)) }
for (const username of Object.keys(result.messages)) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port, username, auth: 'offline', version: '1.21.11' })
  bots.push(bot)
  bot.once('spawn', () => { result.ready.push(username); persist() })
  bot.on('messagestr', message => { result.messages[username].push(message); persist() })
  bot.on('error', error => { result.errors.push(username + ': ' + error.message); persist() })
  bot.on('kicked', reason => { result.errors.push(username + ': kicked ' + String(reason)); persist() })
}
const timer = setInterval(persist, 250)
process.on('SIGTERM', () => {
  clearInterval(timer)
  for (const bot of bots) bot.quit('Local security test complete')
  persist()
  setTimeout(() => process.exit(0), 500)
})
