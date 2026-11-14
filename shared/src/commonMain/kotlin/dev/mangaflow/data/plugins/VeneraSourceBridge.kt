package dev.mangaflow.data.plugins

internal val VENERA_SOURCE_BRIDGE: String = """
/* MangaFlow's independent ComicSource protocol adapter. Source contracts were read
 * from venera-app/venera (ComicSource parser/init.js); no upstream runtime is embedded. */
(() => {
  const enc = JSON.stringify.bind(JSON), dec = JSON.parse.bind(JSON);
  const fail = name => { throw new Error('Unsupported source capability: ' + name); };
  const pref = (op, key, value) => dec(__mfPrefs(enc({op, key, value})));
  const bytes = value => Array.from(value instanceof ArrayBuffer ? new Uint8Array(value) : value || []);
  const buffer = value => new Uint8Array(value).buffer;
  const nativeConvert = (type, value, extra = {}) => dec(__mfCodec(enc({op:'venera', type, value:typeof value === 'string' ? value : bytes(value), ...extra})));
  const utf8 = value => buffer(nativeConvert('utf8',String(value),{isEncode:true}));
  const fromUtf8 = value => nativeConvert('utf8',value,{isEncode:false});
  globalThis.Convert = {
    encodeUtf8:utf8, decodeUtf8:fromUtf8,
    encodeBase64:value => nativeConvert('base64',value,{isEncode:true}),
    decodeBase64:value => buffer(nativeConvert('base64',String(value),{isEncode:false})),
    hexEncode:value => bytes(value).map(v=>(v&255).toString(16).padStart(2,'0')).join(''),
    hexDecode:value => { if(!/^(?:[a-f\d]{2})*${'$'}/i.test(value)) throw new Error('Invalid hex'); return buffer(value.match(/../g)?.map(v=>parseInt(v,16)) || []); },
    md5:value => buffer(nativeConvert('md5',value)), sha1:value => buffer(nativeConvert('sha1',value)),
    sha256:value => buffer(nativeConvert('sha256',value)), sha512:value => buffer(nativeConvert('sha512',value)),
    hmac:(key,value,hash) => buffer(nativeConvert('hmac',value,{key:bytes(key),hash})),
    hmacString:(key,value,hash) => nativeConvert('hmac',value,{key:bytes(key),hash,isString:true}),
    encryptAesEcb:(value,key) => buffer(nativeConvert('aes-ecb',value,{key:bytes(key),isEncode:true})),
    decryptAesEcb:(value,key) => buffer(nativeConvert('aes-ecb',value,{key:bytes(key),isEncode:false})),
    encryptAesCbc:(value,key,iv) => buffer(nativeConvert('aes-cbc',value,{key:bytes(key),iv:bytes(iv),isEncode:true})),
    decryptAesCbc:(value,key,iv) => buffer(nativeConvert('aes-cbc',value,{key:bytes(key),iv:bytes(iv),isEncode:false})),
    encodeGbk:() => fail('GBK encoding'), decodeGbk:() => fail('GBK decoding'),
  };
  globalThis.createUuid = () => __mfCodec(enc({op:'uuid',text:''}));
  class HtmlElement {
    constructor(element) { this.element = element; }
    querySelector(selector) { return wrap(this.element.selectFirst(selector)); }
    querySelectorAll(selector) { return this.element.select(selector).map(wrap); }
    get attributes() { return new Proxy({}, {get:(_,name)=>this.element.attr(String(name))}); }
    get text() { return this.element.text; }
    get innerHTML() { return this.element.innerHtml; }
    get html() { return this.element.html; }
    get outerHTML() { return this.element.outerHtml; }
    get children() { return this.element.children.map(wrap); }
    get parent() { return wrap(this.element.parent); }
    get nextElementSibling() { return wrap(this.element.nextElementSibling); }
    get previousElementSibling() { return wrap(this.element.previousElementSibling); }
    get id() { return this.element.id; }
    get className() { return this.element.className; }
    get localName() { return this.element.localName; }
    get tagName() { return this.element.tagName; }
    get classNames() { return this.className.split(/\s+/).filter(Boolean); }
    getAttribute(name) { return this.element.attr(name); }
    getElementById(id) { return wrap(this.element.getElementById(id)); }
    getElementsByTagName(name) { return this.element.getElementsByTagName(name).map(wrap); }
    dispose() {}
  }
  const wrap = element => element == null ? null : new HtmlElement(element);
  class HtmlDocument extends HtmlElement { constructor(html) { super(new Document(html)); } }
  globalThis.HtmlDocument = HtmlDocument; globalThis.HtmlElement = HtmlElement;
  for(const name of ['Comic','ComicDetails','Comment','ImageLoadingConfig']) globalThis[name] = function(fields) { Object.assign(this,fields); };
  globalThis.APP = Object.freeze({locale:'zh-CN',platform:'mangaflow'});
  globalThis.UI = Object.freeze({showMessage() {}, showDialog:()=>fail('interactive script UI'), showLoading:()=>fail('interactive script UI')});
  let source = null;
  const cookieCache=new Map();
  function cookieKey(url) { return 'cookie:' + new URL(String(url).includes('://')?String(url):'https://'+String(url)).hostname; }
  function readCookies(url) {
    if(!url) return [];
    const key=cookieKey(url);
    if(!cookieCache.has(key)) cookieCache.set(key,pref('get',key) || []);
    const saved=cookieCache.get(key);
    return saved.filter(c=>!c.expires || c.expires>Date.now()).filter(c=>new URL(url).pathname.startsWith(c.path || '/'));
  }
  function storeCookies(url, items) {
    const host = new URL(url).hostname;
    const old = pref('get',cookieKey(url)) || [];
    for(const item of items) {
      if(!item || !/^[!#${'$'}%&'*+.^_`|~\w-]+${'$'}/.test(item.name || '') || typeof item.value !== 'string' || /[\r\n;]/.test(item.value)) continue;
      if(item.domain) { const domain=item.domain.replace(/^\./,'').toLowerCase(); if(!domain.includes('.') || host!==domain && !host.endsWith('.'+domain)) continue; }
      const at = old.findIndex(c=>c.name===item.name && (c.path || '/')===(item.path || '/'));
      const value = {name:item.name,value:item.value,path:item.path || '/',expires:item.expires || 0};
      if(at<0) old.push(value); else old[at]=value;
    }
    if(old.length>64) throw new Error('Source cookie quota exceeded');
    pref('set',cookieKey(url),old); cookieCache.set(cookieKey(url),old);
  }
  function acceptCookies(url, header) {
    if(!header) return;
    const lines = Array.isArray(header) ? header : String(header).split(/,(?=\s*[^;,\s=]+\s*=)/);
    for(const line of lines) {
      const parts=String(line).split(';').map(v=>v.trim()), pair=parts.shift(), split=pair.indexOf('=');
      if(split<=0) continue;
      const cookie={name:pair.slice(0,split),value:pair.slice(split+1)};
      for(const attribute of parts) {
        const index=attribute.indexOf('='), key=(index<0?attribute:attribute.slice(0,index)).toLowerCase(), value=index<0?'':attribute.slice(index+1);
        if(key==='path') cookie.path=value;
        if(key==='domain') cookie.domain=value;
        if(key==='max-age') cookie.expires=Date.now()+Number(value)*1000;
        if(key==='expires' && !cookie.expires) cookie.expires=Date.parse(value);
      }
      storeCookies(url,[cookie]);
    }
  }
  async function request(method,url,headers={},body=null) {
    headers={...headers}; url=String(url);
    const cookies=readCookies(url);
    if(cookies.length && !Object.keys(headers).some(k=>k.toLowerCase()==='cookie')) headers.Cookie=cookies.map(c=>c.name+'='+c.value).join('; ');
    const res=dec(await __mfHttp(enc({method:String(method).toUpperCase(),url,headers,body})));
    const cookie=Object.entries(res.headers || {}).find(([key])=>key.toLowerCase()==='set-cookie');
    if(cookie) acceptCookies(res.url || url,cookie[1]);
    if(res.statusCode===401) pref('remove','session:logged');
    return {status:res.statusCode,body:res.body,headers:res.headers,url:res.url};
  }
  globalThis.Network = Object.freeze({
    get:(url,headers)=>request('GET',url,headers), post:(url,headers,data)=>request('POST',url,headers,data),
    put:(url,headers,data)=>request('PUT',url,headers,data), patch:(url,headers,data)=>request('PATCH',url,headers,data),
    delete:(url,headers,data)=>request('DELETE',url,headers,data), head:(url,headers)=>request('HEAD',url,headers),
    sendRequest:request, getCookies:async url=>readCookies(url), setCookies:async (url,cookies)=>storeCookies(url,cookies),
    deleteCookies:url=>{ cookieCache.delete(cookieKey(url)); return pref('remove',cookieKey(url)); },
    fetch:()=>fail('binary network fetch'),
  });
  globalThis.fetch=async (url,options={})=>{ const res=await request(options.method || 'GET',url,options.headers || {},options.body ?? null); return {status:res.status,ok:res.status>=200 && res.status<300,text:async()=>res.body,json:async()=>dec(res.body)}; };
  class ComicSource {
    static sources = {};
    loadData(key) { if(/^(password|pwd|account)${'$'}/i.test(key)) return null; return pref('get','data:'+String(key)); }
    saveData(key,value) { if(/^(password|pwd|account)${'$'}/i.test(key)) return; return pref('set','data:'+String(key),value); }
    deleteData(key) { return pref('remove','data:'+String(key)); }
    loadSetting(key) {
      const value=pref('get','setting:'+key);
      if(value!=null) return value;
      // A script's optional unattended account mutation is never enabled by its default.
      if(/daily.?check.?in/i.test(key)) return false;
      return this.settings?.[key]?.default ?? null;
    }
    get isLogged() { return pref('get','session:logged')===true; }
    translate(key) { return this.translation?.[APP.locale]?.[key] ?? key; }
    init() {}
  }
  globalThis.ComicSource=ComicSource;
  function validate(provider) {
    if(!(provider instanceof ComicSource) || provider.key!==__mfMetadata.key) throw new Error('ComicSource identity mismatch');
    if(typeof provider.comic?.loadInfo!=='function' || typeof provider.comic?.loadEp!=='function') throw new Error('ComicSource requires comic.loadInfo/loadEp');
    if(provider.name!==__mfMetadata.name || provider.version!==__mfMetadata.version) throw new Error('ComicSource declaration mismatch');
    source=provider; ComicSource.sources[provider.key]=provider;
    if(provider.account?.reLogin) provider.account.reLogin=async()=>{ throw new Error('Session expired; sign in explicitly'); };
    return 'ok';
  }
  function option(value) {
    if(value && typeof value==='object') return {id:String(value.value ?? value.id ?? ''),title:String(value.text ?? value.label ?? value.value ?? '')};
    const text=String(value), at=text.indexOf('-');
    return at<0?{id:text,title:text}:{id:text.slice(0,at),title:text.slice(at+1)};
  }
  function filters(specs) {
    if(!Array.isArray(specs)) return [];
    return specs.map((spec,index)=>{
      const choices=(spec.options || []).map(option);
      if(!choices.length) return null;
      return {id:String(index),title:String(spec.label || spec.title || '筛选'),choices,defaultValue:choices[0].id};
    }).filter(Boolean);
  }
  function filterValues(specs,selected) { return filters(specs).map((filter,index)=>selected?.[index] ?? filter.defaultValue); }
  function comic(value) {
    if(!value || value.id==null || !value.title) throw new Error('Source comic has no id/title');
    let tags=value.tags instanceof Map?Array.from(value.tags.values()).flat():Array.isArray(value.tags)?value.tags:Object.values(value.tags || {}).flat();
    return {id:String(value.id),title:String(value.title),cover:String(value.cover || ''),author:value.subtitle ?? value.subTitle ?? '',genre:tags,description:value.description || ''};
  }
  async function listing(value,page,single=false) {
    let rows,maxPage=1;
    if(Array.isArray(value)) rows=value.flatMap(item=>Array.isArray(item)?item:item?.comics || (item?.id!=null?[item]:[]));
    else if(Array.isArray(value?.comics)) { rows=value.comics; maxPage=Number(value.maxPage || 1); }
    else if(Array.isArray(value?.data)) { rows=value.data.flatMap(item=>Array.isArray(item)?item:item?.comics || (item?.id!=null?[item]:[])); maxPage=Number(value.maxPage || page); }
    else throw new Error('Source returned an invalid comic list');
    if(rows.length>2000 || !Number.isFinite(maxPage)) throw new Error('Source comic list quota exceeded');
    const list=[];
    for(const row of rows) {
      const converted=comic(row);
      const config=converted.cover && source.comic?.onThumbnailLoad?await source.comic.onThumbnailLoad(converted.cover,converted.id):{};
      if(config?.modifyImage || config?.onResponse) fail('cover image transformation');
      if(config?.url) converted.cover=String(config.url);
      converted.headers=config?.headers || {};
      const cookies=readCookies(converted.cover);
      if(cookies.length) converted.headers.Cookie=cookies.map(c=>c.name+'='+c.value).join('; ');
      list.push(converted);
    }
    return {list,hasNextPage:!single && page<maxPage};
  }
  async function feeds(provider) {
    const result=[];
    for(const [index,feed] of (provider.explore || []).entries()) if(typeof feed.load==='function') result.push({id:'explore:'+index,title:String(feed.title || '推荐'),kind:'EXPLORE',filters:[]});
    const ranking=provider.categoryComics?.ranking;
    if(typeof ranking?.load==='function') result.push({id:'ranking',title:'排行榜',kind:'RANKING',filters:[{id:'0',title:'排行',choices:(ranking.options || []).map(option),defaultValue:option((ranking.options || [''])[0]).id}]});
    for(const [partIndex,part] of (provider.category?.parts || []).entries()) {
      if(part.type!=='fixed') continue;
      for(const [index,name] of (part.categories || []).entries()) {
        const canSearch=part.itemType==='search' && typeof provider.search?.load==='function';
        if(!canSearch && typeof provider.categoryComics?.load!=='function') continue;
        let spec=canSearch?provider.search.optionList:provider.categoryComics.optionList;
        if(!canSearch && !spec && typeof provider.categoryComics.optionLoader==='function') {
          // Dynamic options are loaded only when that category is actually opened.
          spec=[];
        }
        result.push({id:'category:'+partIndex+':'+index,title:String(name),kind:'CATEGORY',filters:filters(spec)});
      }
    }
    return result.slice(0,2000);
  }
  async function invoke(provider,method,args) {
    validate(provider);
    const page=Number(args[0] || 1);
    let result,headers={};
    if(!['capabilities','accountState','settings','saveSetting','searchFilters','logout'].includes(method)) await provider.init?.();
    switch(method) {
      case 'capabilities': {
        const features=[];
        if((provider.explore || []).some(feed=>typeof feed.load==='function')) features.push('DISCOVERY');
        if(typeof provider.search?.load==='function') features.push('SEARCH');
        if(typeof provider.categoryComics?.load==='function' || provider.category?.parts?.length) features.push('CATEGORIES');
        if(typeof provider.categoryComics?.ranking?.load==='function') features.push('RANKING');
        if(typeof provider.account?.login==='function') features.push('ACCOUNT');
        if(typeof provider.favorites?.loadComics==='function') features.push('FAVORITES');
        if(typeof provider.comic?.loadComments==='function') features.push('COMMENTS');
        result={features,loginRequired:__mfMetadata.key==='picacg'}; break;
      }
      case 'feeds': result=await feeds(provider); break;
      case 'searchFilters': result=filters(provider.search?.optionList); break;
      case 'feedFilters': {
        const id=String(args[0]);
        if(id==='ranking') { const spec=provider.categoryComics?.ranking; result=filters([{label:'排行',options:spec?.options || []}]); }
        else if(id.startsWith('category:')) {
          const [_,partIndex,index]=id.split(':'),part=provider.category.parts[Number(partIndex)],name=part.categories[Number(index)],param=part.categoryParams?.[Number(index)] ?? null;
          const spec=part.itemType==='search'?provider.search.optionList:provider.categoryComics.optionList ?? await provider.categoryComics.optionLoader?.(name,param) ?? [];
          result=filters(spec);
        } else result=[];
        break;
      }
      case 'getPopular': case 'browse': {
        const feedId=method==='getPopular'?'explore:0':String(args[0]), p=method==='getPopular'?page:Number(args[1]), values=method==='getPopular'?[]:(args[2] || []);
        if(feedId.startsWith('explore:')) {
          const feed=provider.explore?.[Number(feedId.split(':')[1])]; if(!feed?.load) fail('explore');
          const single=feed.type==='multiPartPage';
          result=await listing(await feed.load(single?null:feed.type==='mixed'?p-1:p),p,single);
        } else if(feedId==='ranking') {
          const ranking=provider.categoryComics?.ranking; if(!ranking?.load) fail('ranking');
          result=await listing(await ranking.load(values[0] ?? option(ranking.options[0]).id,p),p);
        } else if(feedId.startsWith('category:')) {
          const [_,partIndex,index]=feedId.split(':'), part=provider.category.parts[Number(partIndex)], name=part.categories[Number(index)], param=part.categoryParams?.[Number(index)] ?? null;
          if(part.itemType==='search') result=await listing(await provider.search.load(name,filterValues(provider.search.optionList,values),p),p);
          else {
            const spec=provider.categoryComics.optionList ?? await provider.categoryComics.optionLoader?.(name,param) ?? [];
            result=await listing(await provider.categoryComics.load(name,param,filterValues(spec,values),p),p);
          }
        } else fail('feed');
        break;
      }
      case 'search': {
        if(typeof provider.search?.load!=='function') fail('search');
        result=await listing(await provider.search.load(String(args[0]),filterValues(provider.search.optionList,args[2]),Number(args[1])),Number(args[1])); break;
      }
      case 'getDetail': {
        const native=String(args[0]), value=await provider.comic.loadInfo(native);
        const chapterMap=value.chapters instanceof Map?Array.from(value.chapters.entries()):Object.entries(value.chapters || {});
        const tags=value.tags instanceof Map?Array.from(value.tags.entries()):Object.entries(value.tags || {});
        const author=tags.filter(([key])=>/author|artist|作者|繪師|绘师/i.test(key)).flatMap(([,values])=>values);
        result={...comic({...value,id:native}),author:author.length?author:(value.subtitle || value.subTitle || ''),chapters:chapterMap.length?chapterMap.map(([id,title])=>({id:String(id),title:String(title)})):[{id:'__mf_single__',title:'正文'}]};
        const cover=provider.comic.onThumbnailLoad?await provider.comic.onThumbnailLoad(result.cover):null;
        if(cover?.modifyImage || cover?.onResponse) fail('cover image transformation');
        if(cover?.url) result.cover=cover.url; headers=cover?.headers || {}; break;
      }
      case 'getPageList': {
        const mangaId=String(args[1]),ep=String(args[0]);
        const data=await provider.comic.loadEp(mangaId,ep==='__mf_single__'?null:ep),images=data?.images;
        if(!Array.isArray(images) || images.length<1 || images.length>10000) throw new Error('Source returned an invalid image list');
        result=images.map(image=>({sourceImageToken:String(image)}));
        break;
      }
      case 'resolveImage': {
        const image=String(args[0]),mangaId=String(args[1]),ep=String(args[2]),episode=ep==='__mf_single__'?null:ep;
        const config=provider.comic.onImageLoad?await provider.comic.onImageLoad(image,mangaId,episode):{};
        if(config?.onResponse || config?.onLoadFailed) fail('binary image response transformation');
        let transform='';
        if(config?.modifyImage) {
          const program=String(config.modifyImage);
          if(provider.key==='jm' && program.includes('Math.floor(image.height / num)') && program.includes('Image.empty(image.width, image.height)') && program.includes('fillImageRangeAt(0, y, image, 0, block.start, image.width, currentHeight)') && program.includes('blocks.length - 1; i >= 0; i--')) transform='jm';
          else fail('image transformation');
        }
        if(config?.method && config.method!=='GET' || config?.data) fail('non-GET image request');
        const url=String(config?.url || image),imageHeaders={...(config?.headers || {})};
        const cookies=readCookies(url); if(cookies.length) imageHeaders.Cookie=cookies.map(c=>c.name+'='+c.value).join('; ');
        result={url,headers:imageHeaders,transform};
        break;
      }
      case 'accountState': result={signedIn:provider.isLogged,displayName:pref('get','session:username') || ''}; break;
      case 'login': {
        if(typeof provider.account?.login!=='function') fail('account login');
        const value=await provider.account.login(String(args[0]),String(args[1]));
        if(value!==true && value!=='ok') throw new Error('Source did not confirm login');
        pref('set','session:logged',true); pref('set','session:username',String(args[0])); result=true; break;
      }
      case 'logout': { try { await provider.account?.logout?.(); } finally { pref('clearSession','session'); cookieCache.clear(); } result=true; break; }
      case 'favoriteFolders': {
        if(!provider.favorites?.loadComics) fail('site favorites');
        if(!provider.favorites.multiFolder) result=[{id:'default',title:'站点收藏'}];
        else {
          const value=await provider.favorites.loadFolders(null), folders=value.folders instanceof Map?Array.from(value.folders.entries()):Object.entries(value.folders || {});
          result=folders.map(([id,title])=>({id:String(id),title:String(title)}));
        }
        break;
      }
      case 'favorites': result=await listing(await provider.favorites.loadComics(Number(args[1]),provider.favorites.multiFolder?String(args[0]):null),Number(args[1])); break;
      case 'setFavorite': {
        if(typeof provider.favorites?.addOrDelFavorite!=='function') fail('edit site favorites');
        const native=String(args[0]), adding=Boolean(args[2]), info=await provider.comic.loadInfo(native);
        if(typeof info.isFavorite==='boolean' && !provider.favorites.multiFolder && info.isFavorite===adding) { result=true; break; }
        if(__mfMetadata.key==='picacg' && typeof info.isFavorite!=='boolean') throw new Error('Source did not return favorite state; no change sent');
        const value=await provider.favorites.addOrDelFavorite(native,provider.favorites.multiFolder?String(args[1]):null,adding);
        if(value!==true && value!=='ok' && value!==undefined) throw new Error('Source did not confirm favorite change');
        result=true; break;
      }
      case 'comments': {
        const info=await provider.comic.loadInfo(String(args[0]));
        const value=await provider.comic.loadComments(String(args[0]),info.subId ?? null,Number(args[1]),null);
        const rows=value.comments || value.data || [];
        result={items:rows.map((row,index)=>({id:String(row.id ?? ('local:'+Number(args[1])+':'+index)),author:String(row.userName || ''),content:String(row.content || ''),avatar:String(row.avatar || ''),time:String(row.time || ''),likes:Number(row.score || 0)})),hasMore:Number(args[1])<Number(value.maxPage || 1)}; break;
      }
      case 'settings': result=Object.entries(provider.settings || {}).filter(([key,spec])=>['input','select','switch'].includes(spec.type)).map(([key,spec])=>({key,title:String(spec.title || key),value:String(provider.loadSetting(key) ?? ''),type:spec.type,choices:(spec.options || []).map(option)})); break;
      case 'saveSetting': {
        const key=String(args[0]), spec=provider.settings?.[key], value=String(args[1]);
        if(!spec || !['input','select','switch'].includes(spec.type) || value.length>4096) fail('setting');
        if(spec.type==='select' && !(spec.options || []).map(option).some(choice=>choice.id===value)) throw new Error('Invalid source setting choice');
        if(typeof spec.validator==='function') { const error=await spec.validator(value); if(error) throw new Error('Invalid source setting'); }
        pref('set','setting:'+key,spec.type==='switch'?value==='true':value); result=true; break;
      }
      default: fail(method);
    }
    return enc({result,headers});
  }
  globalThis.__mfVeneraValidate=validate;
  globalThis.__mfVeneraInvoke=invoke;
})();

""".trimIndent()
