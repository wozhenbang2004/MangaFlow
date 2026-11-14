package dev.mangaflow.data.plugins

/** Shared bounded Mangayomi bootstrap; app asset is retained for installed-version compatibility. */
internal val MANGAYOMI_SOURCE_HOST = """
/* MangaFlow's bounded Mangayomi JavaScript host. Contract reference:
 * kodjodevf/mangayomi-extensions/CONTRIBUTING-JS.md; no browser or system bridge. */
(() => {
  const parse = JSON.parse.bind(JSON);
  const stringify = JSON.stringify.bind(JSON);
  const domCall = request => parse(__mfDom(stringify(request)));
  const nativeHttp = __mfHttp;
  const nativePrefs = __mfPrefs;
  const nativeCodec = __mfCodec;
  const nativeUrl = __mfUrl;
  const unsupported = name => { throw new Error(`Unsupported source host API: ${'$'}{name}`); };
  const element = key => key == null ? null : new Element(key);
  const elements = keys => keys.map(element);
  let defaults = Object.create(null);

  class Element {
    constructor(key) { this.__key = key; }
    _call(op, extra = {}) { return domCall({op, key: this.__key, ...extra}); }
    select(selector) { return elements(this._call('select', {selector: String(selector)})); }
    selectFirst(selector) { return element(this._call('first', {selector: String(selector)})); }
    attr(name) { return this._call('attr', {name: String(name)}); }
    hasAttr(name) { return this._call('hasAttr', {name: String(name)}); }
    getElementById(name) { return element(this._call('byId', {name: String(name)})); }
    getElementsByClassName(name) { return elements(this._call('byClass', {name: String(name)})); }
    getElementsByTagName(name) { return elements(this._call('byTag', {name: String(name)})); }
    get text() { return this._call('text'); }
    get ownText() { return this._call('ownText'); }
    get html() { return this._call('html'); }
    get innerHtml() { return this._call('html'); }
    get outerHtml() { return this._call('outerHtml'); }
    get className() { return this._call('attr', {name: 'class'}); }
    get localName() { return this._call('tag'); }
    get tagName() { return this._call('tag'); }
    get id() { return this._call('attr', {name: 'id'}); }
    get getHref() { return this._call('href'); }
    get getSrc() { return this._call('src'); }
    get getImg() { return this._call('img'); }
    get getDataSrc() { return this._call('dataSrc'); }
    get children() { return elements(this._call('children')); }
    get parent() { return element(this._call('parent')); }
    get parentElement() { return this.parent; }
    get nextElementSibling() { return element(this._call('next')); }
    get previousElementSibling() { return element(this._call('previous')); }
    get siblings() { return elements(this._call('siblings')); }
    xpath() { return unsupported('DOM xpath'); }
    xpathFirst() { return unsupported('DOM xpathFirst'); }
  }
  class Document extends Element {
    constructor(html) { const input = String(html); super(domCall({op: 'parse', html: input})); this.__html = input; }
    get html() { return this.__html; }
    get body() { return element(this._call('body')); }
    get head() { return element(this._call('head')); }
    get documentElement() { return this.selectFirst('html'); }
  }
  class MProvider {
    constructor() { this.source = parse(stringify(globalThis.__mfMetadata)); }
    getFilterList() { return []; }
    getSourcePreferences() { return []; }
    getHeaders() { return {}; }
  }
  class SharedPreferences {
    _call(op, key, value) { return parse(nativePrefs(stringify({op, key, value}))); }
    get(key) {
      const value = this._call('get', String(key));
      return value === null ? (Object.hasOwn(defaults, key) ? defaults[key] : null) : value;
    }
    getString(key, fallback = '') { const value = this.get(key); return value == null ? fallback : String(value); }
    getInt(key, fallback = 0) { const value = this.get(key); return value == null ? fallback : Number(value); }
    getBool(key, fallback = false) { const value = this.get(key); return value == null ? fallback : Boolean(value); }
    setString(key, value) { return this._call('set', String(key), String(value)); }
    setInt(key, value) { return this._call('set', String(key), Number(value)); }
    setBool(key, value) { return this._call('set', String(key), Boolean(value)); }
    set(key, value) { return this._call('set', String(key), value); }
    remove(key) { return this._call('remove', String(key)); }
  }
  class Client {
    async _request(method, url, headers = {}, body = null) {
      if (headers && typeof headers === 'object' && ('headers' in headers || 'body' in headers)) {
        body = headers.body == null ? body : headers.body;
        headers = headers.headers || {};
      }
      return parse(await nativeHttp(stringify({method, url: String(url), headers: headers || {}, body})));
    }
    get(url, headers = {}) { return this._request('GET', url, headers); }
    post(url, headers = {}, body = null) { return this._request('POST', url, headers, body); }
    head(url, headers = {}) { return this._request('HEAD', url, headers); }
    put() { return unsupported('Client.put'); }
    delete() { return unsupported('Client.delete'); }
    patch() { return unsupported('Client.patch'); }
  }
  class SourceURL {
    constructor(value, base) { this._url = parse(nativeUrl(stringify({url: String(value), base: base == null ? '' : String(base)}))); }
    get href() { return this._url.href; }
    get origin() { return this._url.origin; }
    get hostname() { return this._url.hostname; }
    get host() { return this._url.host; }
    get pathname() { return this._url.pathname; }
    get search() { return this._url.search; }
    get hash() { return this._url.hash; }
    get protocol() { return 'https:'; }
    toString() { return this.href; }
  }
  const stringHelpers = {
    substringBefore(pattern) { const text = String(this), at = text.indexOf(pattern); return at < 0 ? text : text.slice(0, at); },
    substringAfter(pattern) { const text = String(this), at = text.indexOf(pattern); return at < 0 ? text : text.slice(at + String(pattern).length); },
    substringBeforeLast(pattern) { const text = String(this), at = text.lastIndexOf(pattern); return at < 0 ? text : text.slice(0, at); },
    substringAfterLast(pattern) { const text = String(this), at = text.lastIndexOf(pattern); return at < 0 ? text : text.slice(at + String(pattern).length); },
    substringBetween(left, right) { const text = String(this), from = text.indexOf(left); if (from < 0) return ''; const start = from + String(left).length, end = text.indexOf(right, start); return end < 0 ? '' : text.slice(start, end); }
  };
  for (const [name, fn] of Object.entries(stringHelpers)) Object.defineProperty(String.prototype, name, {value: fn, configurable: true});
  Object.assign(globalThis, {Document, Element, MProvider, SharedPreferences, Client, URL: SourceURL});
  globalThis.atob = text => nativeCodec(stringify({op: 'atob', text: String(text)}));
  globalThis.btoa = text => nativeCodec(stringify({op: 'btoa', text: String(text)}));
  globalThis.cryptoHandler = (text, iv, key, encrypt) => nativeCodec(stringify({op: 'aesCbc', text: String(text), iv: String(iv), key: String(key), encrypt: Boolean(encrypt)}));
  for (const name of ['encryptAESCryptoJS', 'decryptAESCryptoJS', 'decryptAESGCM', 'unpackJs', 'unpackJsAndCombine', 'deobfuscateJsPassword', 'evaluateJavascriptViaWebview', 'Cloudflare', 'CloudflareBypass', 'WebView', 'fetch', 'require']) {
    globalThis[name] = () => unsupported(name);
  }
  globalThis.console = Object.freeze({log() {}, warn() {}, error() {}, debug() {}});

  function initializePreferences(provider) {
    const specs = provider.getSourcePreferences();
    if (!Array.isArray(specs)) throw new Error('getSourcePreferences must return an array');
    defaults = Object.create(null);
    for (const spec of specs) {
      if (!spec || typeof spec.key !== 'string') continue;
      const pref = spec.listPreference;
      if (pref && Array.isArray(pref.entryValues)) defaults[spec.key] = pref.entryValues[pref.valueIndex || 0];
      else if (spec.switchPreference) defaults[spec.key] = spec.switchPreference.value ?? spec.switchPreference.defaultValue ?? false;
      else if (spec.editTextPreference) defaults[spec.key] = spec.editTextPreference.value ?? spec.editTextPreference.defaultValue ?? '';
      else if (spec.multiSelectListPreference) defaults[spec.key] = spec.multiSelectListPreference.values ?? [];
    }
  }
  function filterDefaults(filters) {
    if (!Array.isArray(filters)) throw new Error('getFilterList must return an array');
    return filters.map(filter => {
      if (!filter || typeof filter !== 'object') throw new Error('Invalid source filter');
      const result = {...filter};
      if (Array.isArray(result.state)) result.state = filterDefaults(result.state);
      else if (result.state == null) result.state = /CheckBox|Switch/.test(result.type_name || '') ? false : 0;
      return result;
    });
  }
  globalThis.__mfValidate = provider => {
    if (!provider || !(provider instanceof MProvider)) throw new Error('DefaultExtension must extend MProvider');
    for (const method of ['search', 'getDetail', 'getPageList']) {
      if (typeof provider[method] !== 'function') throw new Error(`Source does not implement ${'$'}{method}`);
    }
    initializePreferences(provider);
    return 'ok';
  };
  globalThis.__mfInvoke = async (provider, method, args) => {
    __mfValidate(provider);
    if (method === 'capabilities') return stringify({result:{features:[...(typeof provider.getPopular === 'function' ? ['DISCOVERY'] : []), ...(typeof provider.search === 'function' ? ['SEARCH'] : [])], loginRequired:false}, headers:{}});
    const allowed = ['search', 'getDetail', 'getPageList', 'getPopular', 'getLatestUpdates', 'getFilterList', 'getSourcePreferences', 'getHeaders'];
    if (!allowed.includes(method) || typeof provider[method] !== 'function') throw new Error(`Unsupported source operation: ${'$'}{method}`);
    if (method === 'search' && (!Array.isArray(args[2]) || args[2].length === 0)) args[2] = filterDefaults(provider.getFilterList());
    const result = await provider[method](...args);
    if (result === undefined) throw new Error(`${'$'}{method} did not return a result`);
    let headerUrl = provider.source.baseUrl;
    if ((method === 'getDetail' || method === 'getPageList') && typeof args[0] === 'string') headerUrl = new SourceURL(args[0], headerUrl).href;
    const headers = await provider.getHeaders(headerUrl);
    if (headers == null || typeof headers !== 'object' || Array.isArray(headers)) throw new Error('getHeaders must return an object');
    return stringify({result, headers});
  };
})();

""".trimIndent()
