package dev.mangaflow.anime.sources.lanerc

/** Independently implemented compatibility surface; contains no third-party source implementation. */
internal val LANERC_JAVASCRIPT_HOST = """
(function (g) {
  'use strict';
  function options(value) {
    if (typeof value === 'string') return JSON.parse(value || '{}');
    return value || {};
  }
  function text(value) { return value == null ? '' : String(value); }
  function wire(url, opts, method, body) {
    var input = options(opts), headers = options(input.headers);
    if (!Object.keys(headers).some(function (name) { return name.toLowerCase() === 'user-agent'; })) {
      var agent = text(input.userAgent || input.ua);
      if (agent) headers['User-Agent'] = (g.UA && g.UA[agent.toLowerCase()]) || agent;
    }
    var request = {url: text(url), method: method || text(input.method || 'GET').toUpperCase(),
      headers: headers, responseEncoding: input.responseEncoding || input.charset || 'utf8',
      bodyEncoding: input.bodyEncoding || 'utf8', noCookie: input.noCookie === true};
    if (body != null) request.body = typeof body === 'object' ? JSON.stringify(body) : text(body);
    else if (input.body != null) request.body = input.body;
    if (input.timeout != null) request.timeout = Number(input.timeout);
    if (input.followRedirects === false) request.followRedirects = false;
    var reply = JSON.parse(__mlHttp(JSON.stringify(request)));
    reply.status = reply.statusCode;
    reply.ok = reply.status >= 200 && reply.status < 300;
    return reply;
  }
  g.request = function (url, opts) { return wire(url, opts).body; };
  g.get = function (url, opts) { return wire(url, opts, 'GET').body; };
  g.post = function (url, body, opts) { return wire(url, opts, 'POST', body || '').body; };
  g.http = {
    request: g.request, get: g.get, post: g.post,
    request2: function (url, opts) { return wire(url, opts); },
    post2: function (url, body, opts) { return wire(url, opts, 'POST', body || ''); },
    resolveRedirect: function (url, opts) { return wire(url, opts, 'GET').url; },
    getCookies: function (host) { return JSON.parse(__mlPrefs(JSON.stringify({op:'cookiesGet', key:text(host)}))); },
    setCookies: function (host, value) { __mlPrefs(JSON.stringify({op:'cookiesSet', key:text(host), value:value || {}})); },
    clearCookies: function (host) { __mlPrefs(JSON.stringify({op:'cookiesClear', key:text(host)})); }
  };
  function codec(operation, value, key, opts, algorithm, encrypt) {
    return __mlCodec(JSON.stringify({operation:operation, text:text(value), key:text(key),
      options:options(opts), algorithm:algorithm || '', encrypt:!!encrypt}));
  }
  g.crypto = {
    hash: function (algorithm, value, opts) { return codec('hash', value, '', opts, algorithm); },
    hmac: function (algorithm, key, value, opts) { return codec('hmac', value, key, opts, algorithm); },
    base64: {
      encode: function (value, opts) { return codec('base64Encode', value, '', opts); },
      decode: function (value, opts) { return codec('base64Decode', value, '', opts); }
    },
    hex: {
      encode: function (value, opts) { return codec('hexEncode', value, '', opts); },
      decode: function (value, opts) { return codec('hexDecode', value, '', opts); }
    },
    gzip: { decode: function (value, opts) { return codec('gzip', value, '', opts); } },
    inflate: function (value, opts) { return codec('inflate', value, '', opts); },
    pbkdf2: function (password, salt, iterations, bits, algorithm, opts) {
      return __mlCodec(JSON.stringify({operation:'pbkdf2', text:text(password), salt:text(salt),
        iterations:Math.floor(iterations || 1000), bits:Math.floor(bits || 256),
        algorithm:algorithm || 'SHA-256', options:options(opts)}));
    }
  };
  ['md5', 'sha1', 'sha256', 'sha512'].forEach(function (name) {
    var fn = function (value) { return codec('hash', value, '', {}, name); };
    g[name] = fn; g.crypto[name] = fn;
  });
  ['aes', 'des', 'tripleDes', 'rsa'].forEach(function (name) {
    g.crypto[name] = {
      encrypt: function (value, key, opts) { return codec(name, value, key, opts, '', true); },
      decrypt: function (value, key, opts) { return codec(name, value, key, opts, '', false); }
    };
  });
  g.base64Encode = function (value) { return g.crypto.base64.encode(value); };
  g.base64Decode = function (value) { return g.crypto.base64.decode(value); };
  g.atob = function (value) { return g.crypto.base64.decode(value, {output:'latin1'}); };
  g.btoa = function (value) { return g.crypto.base64.encode(value, {input:'latin1'}); };
  g.encodeUri = function (value) { return encodeURIComponent(text(value)).replace(/%20/g, '+').replace(/[!~'()]/g, function (ch) { return '%' + ch.charCodeAt(0).toString(16).toUpperCase(); }); };
  g.decodeUri = function (value) { try { return decodeURIComponent(text(value).replace(/\+/g, ' ')); } catch (_) { return text(value); } };
  function regex(value, global) {
    if (value instanceof RegExp) return new RegExp(value.source, global ? 'g' + value.flags.replace(/g/g, '') : value.flags);
    var pattern = text(value), flags = global ? 'gs' : 's';
    if (pattern.indexOf('(?is)') === 0) { pattern = pattern.slice(5); flags += 'i'; }
    else if (pattern.indexOf('(?s)') === 0) { pattern = pattern.slice(4); /* dotAll is the host default */ }
    else if (pattern.indexOf('(?i)') === 0) { pattern = pattern.slice(4); flags += 'i'; }
    return new RegExp(pattern, flags);
  }
  g.match = function (value, pattern, group) {
    var result = regex(pattern, false).exec(text(value));
    return result ? text(result[group == null ? 0 : Number(group)]) : '';
  };
  g.matchAll = function (value, pattern) {
    var expression = regex(pattern, true), result, items = [], input = text(value);
    while ((result = expression.exec(input)) !== null) {
      items.push(Array.prototype.map.call(result, function (value) { return text(value); }));
      if (result[0].length === 0) expression.lastIndex++;
      if (items.length > 10000) throw new Error('Result limit');
    }
    return JSON.stringify(items);
  };
  g.timestamp = function () { return Date.now(); };
  g.log = function () {};
  g.console = {log:g.log, warn:g.log, error:g.log, info:g.log, debug:g.log};
  g.getItem = function (key, fallback) {
    var value = JSON.parse(__mlPrefs(JSON.stringify({op:'get', key:text(key)})));
    return value == null ? text(fallback) : text(value);
  };
  g.setItem = function (key, value) { __mlPrefs(JSON.stringify({op:'set', key:text(key), value:text(value)})); };
  g.removeItem = function (key) { __mlPrefs(JSON.stringify({op:'remove', key:text(key)})); };
  g.parseJson = function (value) { try { return JSON.parse(value || 'null'); } catch (_) { return null; } };
  g.toJson = function (value) { try { return JSON.stringify(value); } catch (_) { return ''; } };
  g.sniffMedia = g.sniffAll = function () { return __mlUnsupported('sniff'); };
  g.UA = {
    chrome:'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36',
    desktop:'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
  };
  g.UA.mobile = g.UA.chrome; g.UA.android = g.UA.chrome; g.UA.pc = g.UA.desktop;
  g.UA.edge = g.UA.desktop; g.UA.firefox = 'Mozilla/5.0 (X11; Linux x86_64; rv:120.0) Gecko/20100101 Firefox/120.0';
  g.UA.iphone = 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1';
  g.UA.ipad = 'Mozilla/5.0 (iPad; CPU OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1';
  g.UA.safari = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15';
  g.__mlInvoke = function (method, args, sourceId, exactLanercDanmaku) {
    if (typeof g[method] !== 'function') return JSON.stringify({missing:true});
    var value;
    // The observed AniCh script silently substitutes got[0] when a selected slug disappears.
    // Its documented flag/list helper contract lets this host require that exact line instead.
    if (sourceId === 'anime-lanerc-ani' && method === 'play') {
      if (typeof g._decFlag !== 'function' || typeof g._vodById !== 'function') return JSON.stringify({selectionUnsupported:true});
      var selection = g._decFlag(args[0]);
      if (!selection || !selection.id || !selection.sort || !selection.slug) return JSON.stringify({selectionChanged:true});
      var rows = g._vodById(selection.id, selection.sort);
      var matches = Array.isArray(rows) ? rows.filter(function (row) { return row.slug === selection.slug; }) : [];
      if (matches.length !== 1) return JSON.stringify({selectionChanged:true});
      value = {url:matches[0].url, type:matches[0].type || 'auto'};
    } else if (sourceId === 'anime-lanerc-lanerc' && method === 'danmu') {
      if (!exactLanercDanmaku) return JSON.stringify({selectionUnsupported:true});
      // The pinned script normally recalls a title. Its episode flag already
      // identifies the exact content and the zero-based native episode index.
      var episode = JSON.parse(args[0]);
      if (!episode || !/^[A-Za-z0-9_-]{1,256}$/.test(episode.contentId) ||
          !Number.isInteger(episode.episodeIndex) || episode.episodeIndex < 0 || episode.episodeIndex > 20000)
        return JSON.stringify({selectionChanged:true});
      g._lastDetail = {id:episode.contentId, name:''};
      value = g.danmu(JSON.stringify({episode:episode.episodeIndex}));
    } else value = g[method].apply(g, args);
    if (value && typeof value.then === 'function') return JSON.stringify({asyncUnsupported:true});
    if (typeof value === 'string') { try { value = JSON.parse(value); } catch (_) { return JSON.stringify({invalid:true}); } }
    return JSON.stringify({result:value == null ? null : value});
  };
})(globalThis);
""".trimIndent()
