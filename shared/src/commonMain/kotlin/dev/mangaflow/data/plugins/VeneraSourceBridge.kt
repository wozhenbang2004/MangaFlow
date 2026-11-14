package dev.mangaflow.data.plugins

internal val VENERA_SOURCE_BRIDGE: String = """
/* MangaFlow's independent ComicSource protocol adapter. Source contracts were read
 * from venera-app/venera (ComicSource parser/init.js); no upstream runtime is embedded. */
(() => {
  const enc = JSON.stringify.bind(JSON), dec = JSON.parse.bind(JSON);
  const fail = name => { throw new Error('Unsupported source capability: ' + name); };
  let stagedPreferences = null;
  const nativePref = (op,key,value) => dec(__mfPrefs(enc({op,key,value})));
  function pref(op,key,value) {
    if(stagedPreferences) {
      if(op==='get' && stagedPreferences.has(key)) {
        const change=stagedPreferences.get(key); return change.op==='remove'?null:change.value;
      }
      if(op==='set' || op==='remove') {
        if(stagedPreferences.size>=128 && !stagedPreferences.has(key)) throw new Error('Source session quota exceeded');
        stagedPreferences.set(key,{op,key,value}); return true;
      }
    }
    return nativePref(op,key,value);
  }
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
    get attributes() { return new Proxy({}, {get:(_,name)=>typeof name==='string' && this.element.hasAttr(name)?this.element.attr(name):undefined}); }
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
    getAttribute(name) { return this.element.hasAttr(name)?this.element.attr(name):null; }
    getElementById(id) { return wrap(this.element.getElementById(id)); }
    getElementsByTagName(name) { return this.element.getElementsByTagName(name).map(wrap); }
    dispose() {}
  }
  const wrap = element => element == null ? null : new HtmlElement(element);
  class HtmlDocument extends HtmlElement { constructor(html) { super(new Document(html)); } }
  globalThis.HtmlDocument = HtmlDocument; globalThis.HtmlElement = HtmlElement;
  for(const name of ['Comic','ComicDetails','Comment','ImageLoadingConfig','Cookie']) globalThis[name] = function(fields) { Object.assign(this,fields); };
  globalThis.APP = Object.freeze({locale:'zh-CN',platform:'mangaflow'});
  globalThis.UI = Object.freeze({showMessage() {}, showDialog:()=>fail('interactive script UI'), showLoading:()=>fail('interactive script UI')});
  let source = null;
  const cookieCache=new Map();
  const cookieUrl = url => new URL(String(url).includes('://')?String(url):'https://'+String(url));
  function cookieKeys(url) {
    const parts=cookieUrl(url).hostname.toLowerCase().split('.'),keys=[];
    for(let i=0;i<parts.length-1;i++) keys.push('cookie:'+parts.slice(i).join('.'));
    return keys;
  }
  function cookieJar(key) {
    if(!cookieCache.has(key)) cookieCache.set(key,pref('get',key) || []);
    return cookieCache.get(key);
  }
  function readCookies(url) {
    if(!url) return [];
    const target=cookieUrl(url),path=target.pathname || '/',cookies=[];
    for(const key of cookieKeys(url)) for(const item of cookieJar(key)) {
      const domain=item.domain || key.slice(7),hostOnly=item.hostOnly ?? true,prefix=item.path || '/';
      if(hostOnly?target.hostname!==domain:target.hostname!==domain && !target.hostname.endsWith('.'+domain)) continue;
      if(item.secure && target.protocol!=='https:' || item.expires && item.expires<=Date.now()) continue;
      if(path!==prefix && !(path.startsWith(prefix) && (prefix.endsWith('/') || path[prefix.length]==='/'))) continue;
      cookies.push({...item,domain:hostOnly?domain:'.'+domain});
    }
    return cookies.sort((a,b)=>(b.path || '/').length-(a.path || '/').length);
  }
  function storeCookies(url, items) {
    const host = cookieUrl(url).hostname.toLowerCase(),updated=new Map();
    if(!Array.isArray(items) || items.length>64) throw new Error('Source cookie quota exceeded');
    for(const item of items) {
      if(!item || !/^[!#${'$'}%&'*+.^_`|~\w-]+${'$'}/.test(item.name || '') || typeof item.value !== 'string' || /[\r\n;]/.test(item.value)) continue;
      const domain=String(item.domain || host).replace(/^\./,'').toLowerCase();
      if(!domain.includes('.') || host!==domain && !host.endsWith('.'+domain)) continue;
      const key='cookie:'+domain;
      if(!updated.has(key)) updated.set(key,cookieJar(key).filter(c=>!c.expires || c.expires>Date.now()).map(c=>({...c})));
      const old=updated.get(key),path=String(item.path || '/');
      if(!path.startsWith('/') || path.length>2048) continue;
      const at=old.findIndex(c=>c.name===item.name && (c.path || '/')===path);
      const value={name:item.name,value:item.value,path,domain,hostOnly:item.hostOnly ?? !item.domain,
        secure:Boolean(item.secure),expires:Number(item.expires) || 0};
      if(value.expires && value.expires<=Date.now()) { if(at>=0) old.splice(at,1); }
      else if(at<0) old.push(value); else old[at]=value;
    }
    for(const [key,old] of updated) {
      if(old.length>64) throw new Error('Source cookie quota exceeded');
      pref('set',key,old); cookieCache.set(key,old);
    }
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
        if(key==='secure') cookie.secure=true;
        if(key==='max-age') cookie.expires=Date.now()+Number(value)*1000;
        if(key==='expires' && !cookie.expires) cookie.expires=Date.parse(value);
      }
      storeCookies(url,[cookie]);
    }
  }
  async function request(method,url,headers={},body=null) {
    headers={...headers}; url=String(url);
    const cookies=readCookies(url);
    const explicit=Object.keys(headers).find(k=>k.toLowerCase()==='cookie');
    const names=new Set(String(explicit?headers[explicit]:'').split(';').map(pair=>pair.trim().split('=')[0]));
    const automatic=cookies.filter(cookie=>!names.has(cookie.name)).map(c=>c.name+'='+c.value).join('; ');
    if(automatic) headers[explicit || 'Cookie']=automatic+(explicit && headers[explicit]?'; '+headers[explicit]:'');
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
    deleteCookies:url=>{ for(const key of cookieKeys(url)) { cookieCache.delete(key); pref('remove',key); } return true; },
    fetch:()=>fail('binary network fetch'),
  });
  globalThis.fetch=async (url,options={})=>{ const res=await request(options.method || 'GET',url,options.headers || {},options.body ?? null); return {status:res.status,ok:res.status>=200 && res.status<300,text:async()=>res.body,json:async()=>dec(res.body)}; };
  class ComicSource {
    static sources = {};
    loadData(key) { if(/^(password|pwd|account)${'$'}/i.test(key)) return null; return pref('get','data:'+String(key)); }
    saveData(key,value) { if(/^(password|pwd|account)${'$'}/i.test(key)) return; return pref('set','data:'+String(key),value); }
    deleteData(key) { return pref('remove','data:'+String(key)); }
    loadSetting(key) {
      if(this.settings?.[key]?.mangaflowSupported===false) return false;
      const value=pref('get','setting:'+key);
      if(value!=null) return value;
      // A script's optional unattended account mutation is never enabled by its default.
      if(/daily.?check.?in/i.test(key)) return false;
      return this.settings?.[key]?.default ?? null;
    }
    get isLogged() { return pref('get','session:logged')===true; }
    translate(key) { return this.translation?.[APP.locale]?.[key] ?? this.translation?.[APP.locale.replace('-','_')]?.[key] ?? key; }
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
      const multiple=spec.type==='multi-select',allowed=new Set(choices.map(choice=>choice.id));
      const selected=multiple?(Array.isArray(spec.default)?spec.default:[]).map(String).filter(id=>allowed.has(id)):[];
      const defaultValue=multiple?enc([...new Set(selected)]):allowed.has(String(spec.default))?String(spec.default):choices[0].id;
      return {id:String(index),title:String(spec.label || spec.title || '筛选'),choices,defaultValue,multiple};
    }).filter(Boolean);
  }
  function filterValues(specs,selected) { return filters(specs).map((filter,index)=>selected?.[index] ?? filter.defaultValue); }
  function comic(value) {
    if(!value || value.id==null || !value.title) throw new Error('Source comic has no id/title');
    let tags=value.tags instanceof Map?Array.from(value.tags.values()).flat():Array.isArray(value.tags)?value.tags:Object.values(value.tags || {}).flat();
    return {id:String(value.id),title:String(value.title),cover:String(value.cover || ''),author:value.subtitle ?? value.subTitle ?? '',genre:tags,description:value.description || ''};
  }
  const hasLoader = value => typeof value?.load==='function' || typeof value?.loadNext==='function';
  function nextInput(args,page) {
    if(page===1) return null;
    const cursor=args[3];
    if(typeof cursor!=='string' || !cursor || cursor.length>8192) throw new Error('Source cursor expired; refresh this list');
    return cursor;
  }
  async function listing(value,page,single=false,cursorMode=false,input=null) {
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
    if(cursorMode) {
      const next=value?.next ?? null;
      if(next!==null && (typeof next!=='string' || !next || next.length>8192 || /[\u0000-\u001f\u007f]/.test(next) || next===input))
        throw new Error('Invalid source continuation');
      return {list,hasNextPage:next!==null,cursorMode:true,nextCursor:next};
    }
    return {list,hasNextPage:!single && page<maxPage};
  }
  async function feeds(provider) {
    const result=[];
    for(const [index,feed] of (provider.explore || []).entries()) if(hasLoader(feed)) result.push({id:'explore:'+index,title:String(feed.title || '推荐'),kind:'EXPLORE',filters:[]});
    const ranking=provider.categoryComics?.ranking;
    if(hasLoader(ranking)) result.push({id:'ranking',title:'排行榜',kind:'RANKING',filters:[{id:'0',title:'排行',choices:(ranking.options || []).map(option),defaultValue:option((ranking.options || [''])[0]).id}]});
    for(const [partIndex,part] of (provider.category?.parts || []).entries()) {
      if(part.type!=='fixed') continue;
      for(const [index,name] of (part.categories || []).entries()) {
        const canSearch=part.itemType==='search' && hasLoader(provider.search);
        if(!canSearch && !hasLoader(provider.categoryComics)) continue;
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
    if(!['capabilities','accountState','settings','saveSetting','searchFilters','logout','login','loginCookies'].includes(method)) await provider.init?.();
    switch(method) {
      case 'capabilities': {
        const features=[];
        if((provider.explore || []).some(hasLoader)) features.push('DISCOVERY');
        if(hasLoader(provider.search)) features.push('SEARCH');
        if(hasLoader(provider.categoryComics) || provider.category?.parts?.length) features.push('CATEGORIES');
        if(hasLoader(provider.categoryComics?.ranking)) features.push('RANKING');
        const passwordLogin=typeof provider.account?.login==='function';
        const cookieFields=typeof provider.account?.loginWithCookies?.validate==='function'?provider.account.loginWithCookies.fields || []:[];
        if(cookieFields.length>16 || cookieFields.some(field=>typeof field!=='string' || !field || field.length>160)) throw new Error('Invalid source login fields');
        if(passwordLogin || cookieFields.length) features.push('ACCOUNT');
        if(typeof provider.favorites?.loadComics==='function' || typeof provider.favorites?.loadNext==='function') features.push('FAVORITES');
        if(typeof provider.comic?.loadComments==='function') features.push('COMMENTS');
        result={features,loginRequired:__mfMetadata.key==='picacg',passwordLogin,cookieFields}; break;
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
          const feed=provider.explore?.[Number(feedId.split(':')[1])]; if(!hasLoader(feed)) fail('explore');
          const single=feed.type==='multiPartPage';
          if(typeof feed.load==='function') result=await listing(await feed.load(single?null:feed.type==='mixed'?p-1:p),p,single);
          else { const cursor=nextInput(args,p); result=await listing(await feed.loadNext(cursor),p,false,true,cursor); }
        } else if(feedId==='ranking') {
          const ranking=provider.categoryComics?.ranking; if(!hasLoader(ranking)) fail('ranking');
          const selected=values[0] ?? option(ranking.options[0]).id;
          if(typeof ranking.load==='function') result=await listing(await ranking.load(selected,p),p);
          else { const cursor=nextInput(args,p); result=await listing(await ranking.loadNext(selected,cursor),p,false,true,cursor); }
        } else if(feedId.startsWith('category:')) {
          const [_,partIndex,index]=feedId.split(':'), part=provider.category.parts[Number(partIndex)], name=part.categories[Number(index)], param=part.categoryParams?.[Number(index)] ?? null;
          if(part.itemType==='search') {
            const selected=filterValues(provider.search.optionList,values);
            if(typeof provider.search.load==='function') result=await listing(await provider.search.load(name,selected,p),p);
            else { const cursor=nextInput(args,p); result=await listing(await provider.search.loadNext(name,selected,cursor),p,false,true,cursor); }
          } else {
            const spec=provider.categoryComics.optionList ?? await provider.categoryComics.optionLoader?.(name,param) ?? [];
            const selected=filterValues(spec,values);
            if(typeof provider.categoryComics.load==='function') result=await listing(await provider.categoryComics.load(name,param,selected,p),p);
            else { const cursor=nextInput(args,p); result=await listing(await provider.categoryComics.loadNext(name,param,selected,cursor),p,false,true,cursor); }
          }
        } else fail('feed');
        break;
      }
      case 'search': {
        if(!hasLoader(provider.search)) fail('search');
        const p=Number(args[1]),selected=filterValues(provider.search.optionList,args[2]);
        if(typeof provider.search.load==='function') result=await listing(await provider.search.load(String(args[0]),selected,p),p);
        else { const cursor=nextInput(args,p); result=await listing(await provider.search.loadNext(String(args[0]),selected,cursor),p,false,true,cursor); }
        break;
      }
      case 'getDetail': {
        const native=String(args[0]), value=await provider.comic.loadInfo(native);
        const chapterMap=value.chapters instanceof Map?Array.from(value.chapters.entries()):Object.entries(value.chapters || {});
        const tags=value.tags instanceof Map?Array.from(value.tags.entries()):Object.entries(value.tags || {});
        const author=tags.filter(([key])=>/author|artist|作者|繪師|绘师/i.test(key)).flatMap(([,values])=>values);
        result={...comic({...value,id:native}),author:author.length?author:(value.subtitle || value.subTitle || ''),chapters:chapterMap.length?chapterMap.map(([id,title])=>({id:String(id),title:String(title)})):[{id:'__mf_single__',title:'正文'}]};
        const cover=provider.comic.onThumbnailLoad?await provider.comic.onThumbnailLoad(result.cover):null;
        if(cover?.modifyImage || cover?.onResponse) fail('cover image transformation');
        if(cover?.url) result.cover=cover.url;
        const coverHeaders={...(cover?.headers || {})},cookies=readCookies(result.cover);
        if(cookies.length) coverHeaders.Cookie=cookies.map(c=>c.name+'='+c.value).join('; ');
        result.headers=coverHeaders; break;
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
        let config=provider.comic.onImageLoad?await provider.comic.onImageLoad(image,mangaId,episode):{};
        if(args[3]===true && typeof config?.onLoadFailed==='function') config=await config.onLoadFailed();
        if(config?.onResponse) fail('binary image response transformation');
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
      case 'login': case 'loginCookies': {
        stagedPreferences=new Map();
        try {
          await provider.init?.();
          let value;
          if(method==='login') {
            if(typeof provider.account?.login!=='function') fail('account login');
            value=await provider.account.login(String(args[0]),String(args[1]));
          } else {
            const login=provider.account?.loginWithCookies,values=args[0];
            if(typeof login?.validate!=='function') fail('cookie login');
            if(!Array.isArray(values) || values.length!==login.fields.length || values.some(v=>typeof v!=='string' || v.length>8192 || /[\r\n;]/.test(v))) throw new Error('Invalid source login values');
            value=await login.validate(values);
          }
          if(value!==true && value!=='ok') throw new Error('Source did not confirm login');
          pref('set','session:logged',true); pref('set','session:username',method==='login'?String(args[0]):'');
          nativePref('batch','session',Array.from(stagedPreferences.values())); result=true;
        } finally { stagedPreferences=null; }
        break;
      }
      case 'logout': { try { await provider.account?.logout?.(); } finally { pref('clearSession','session'); cookieCache.clear(); } result=true; break; }
      case 'favoriteFolders': {
        if(typeof provider.favorites?.loadComics!=='function' && typeof provider.favorites?.loadNext!=='function') fail('site favorites');
        if(!provider.favorites.multiFolder) result=[{id:'default',title:'站点收藏'}];
        else {
          const value=await provider.favorites.loadFolders(null), folders=value.folders instanceof Map?Array.from(value.folders.entries()):Object.entries(value.folders || {});
          result=folders.map(([id,title])=>({id:String(id),title:String(title)}));
        }
        break;
      }
      case 'favorites': {
        const p=Number(args[1]),folder=provider.favorites.multiFolder?String(args[0]):null;
        if(typeof provider.favorites.loadComics==='function') result=await listing(await provider.favorites.loadComics(p,folder),p);
        else { const cursor=nextInput(args,p); result=await listing(await provider.favorites.loadNext(cursor,folder),p,false,true,cursor); }
        break;
      }
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
      case 'settings': result=Object.entries(provider.settings || {}).filter(([key,spec])=>spec.mangaflowSupported!==false && ['input','select','switch'].includes(spec.type)).map(([key,spec])=>({key,title:String(provider.translate(spec.title || key)),value:String(provider.loadSetting(key) ?? ''),type:spec.type,choices:(spec.options || []).map(option)})); break;
      case 'saveSetting': {
        const key=String(args[0]), spec=provider.settings?.[key], value=String(args[1]);
        if(!spec || spec.mangaflowSupported===false || !['input','select','switch'].includes(spec.type) || value.length>4096) fail('setting');
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
