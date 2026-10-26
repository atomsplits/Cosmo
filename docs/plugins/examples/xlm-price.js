// XLM price: the live price of XLM in US dollars, its 24-hour change as a sparkline, what your
// balance is worth, and an optional alert when the price crosses a level you pick.
//
// Plugins can't go online, so the app passes the price in as data.price. It comes from Stellar's
// own exchange (XLM traded against USDC on the public network) and is refreshed every minute.

var BARS = '▁▂▃▄▅▆▇█';
var alertAt = Number(cosmo.storage.get('alertAt') || 0);
var lastPrice = null;

function usd(value) {
  return '$' + value.toFixed(value < 1 ? 4 : 2);
}

// One bar per hour, scaled between the day's low and high.
function sparkline(values) {
  if (values.length < 2) return '';
  var low = Math.min.apply(null, values);
  var high = Math.max.apply(null, values);
  var range = high - low || 1;
  return values.map(function (v) {
    return BARS.charAt(Math.round((v - low) / range * (BARS.length - 1)));
  }).join('');
}

function setAlert(value) {
  alertAt = value;
  if (value) cosmo.storage.set('alertAt', String(value));
  else cosmo.storage.remove('alertAt');
}

cosmo.registerWidget({
  id: 'price',
  title: 'XLM price',
  render: function (data) {
    var price = data.price;
    if (!price) {
      return ui.column(
        ui.title('XLM price'),
        ui.text('Waiting for the latest price…', { style: 'caption' })
      );
    }

    // The price moved across the alert level since the last update.
    if (alertAt && lastPrice !== null && (lastPrice < alertAt) !== (price.usd < alertAt)) {
      cosmo.toast('XLM is ' + (price.usd >= alertAt ? 'above ' : 'below ') + usd(alertAt) + ': now ' + usd(price.usd));
    }
    lastPrice = price.usd;

    var change = price.change24h;
    var changeText = change === null
      ? 'No trades to compare yet'
      : (change >= 0 ? '▲ Up ' : '▼ Down ') + Math.abs(change).toFixed(2) + '% in 24 hours';
    var changeColor = change === null ? undefined : (change >= 0 ? '#2E7D32' : '#C62828');

    var worth = Number(data.xlm) * price.usd;
    var worthText = data.network === 'public'
      ? 'Your ' + Number(data.xlm).toFixed(2) + ' XLM ≈ ' + usd(worth)
      : 'Your test XLM has no real value (it would be ' + usd(worth) + ')';

    return ui.column(
      ui.text(usd(price.usd), { style: 'headline' }),
      ui.text(changeText, { style: 'label', color: changeColor }),
      ui.text(sparkline(price.history), { style: 'title' }),
      ui.text(worthText, { style: 'caption' }),
      ui.divider(),
      ui.text(alertAt ? 'Alert when XLM crosses ' + usd(alertAt) : 'No price alert set', { style: 'label' }),
      ui.row(
        ui.button('+5%', function () { setAlert(Number((price.usd * 1.05).toPrecision(4))); }),
        ui.button('−5%', function () { setAlert(Number((price.usd * 0.95).toPrecision(4))); }),
        ui.button('Clear', function () { setAlert(0); })
      ),
      ui.text('Stellar DEX (XLM/USDC) · ' + price.updatedAt.substr(11, 5) + ' UTC', { style: 'caption' })
    );
  }
});
