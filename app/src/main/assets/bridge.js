/* ZFLOS bridge for Android. Injected at the top of every package. Version 202611201809 */
(function () {
  var p = {}, n = 0;
  window.__zflReply = function (id, s) {
    var q = p[id]; if (!q) return; delete p[id];
    var m = JSON.parse(s);
    if (m.ok) q.res(m.v); else q.rej(new Error(m.v));
  };
  function call(fn, a) {
    return new Promise(function (res, rej) {
      var id = ++n; p[id] = { res: res, rej: rej };
      try { ZflNative.call(id, fn, JSON.stringify(a || {})); }
      catch (e) { delete p[id]; rej(e); }
    });
  }
  window.zfl = {
    version: 2, host: 'android', call: call,
    storage: {
      get: function (k) { return call('storage.get', { k: k }); },
      set: function (k, v) { return call('storage.set', { k: k, v: v }); },
      remove: function (k) { return call('storage.remove', { k: k }); }
    },
    notify: function (title, body) { return call('notify', { title: title, body: body }); },
    clipboard: { write: function (t) { return call('clipboard.write', { text: t }); } },
    overlay: {
      show: function (text, opts) { return call('overlay.show', { text: text, opts: opts || {} }); },
      hide: function () { return call('overlay.hide'); }
    },
    screenshot: { pick: function () { return call('screenshot.pick'); } },
    share: {
      text: function (t, title) { return call('share.text', { text: t, title: title }); },
      image: function (dataUrl, title) { return call('share.image', { dataUrl: dataUrl, title: title }); }
    },
    app: { toBack: function () { return call('app.toBack'); } },
    vibrate: function (ms) { return call('vibrate', { ms: ms }); },
    close: function () { return call('close'); }
  };
})();
