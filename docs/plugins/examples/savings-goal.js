// Savings goal: shows how close your XLM balance is to a target you set with the buttons.
// The goal is saved with cosmo.storage, so it's still there next time you open the app.

var goal = Number(cosmo.storage.get('goal') || 1000);

function setGoal(value) {
  goal = Math.max(100, value);
  cosmo.storage.set('goal', goal);
}

cosmo.registerWidget({
  id: 'goal',
  title: 'Savings goal',
  render: function (data) {
    var balance = Number(data.xlm);
    var progress = Math.min(1, balance / goal);
    return ui.column(
      ui.title('Goal: ' + goal + ' XLM'),
      ui.progress(progress),
      ui.text(Math.round(progress * 100) + '% there (' + balance.toFixed(2) + ' XLM)', { style: 'caption' }),
      ui.row(
        ui.button('− 100', function () { setGoal(goal - 100); }),
        ui.button('+ 100', function () {
          setGoal(goal + 100);
          if (balance >= goal) cosmo.toast('You already have enough!');
        })
      )
    );
  }
});
