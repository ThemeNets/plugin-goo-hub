package com.themenets.plugingoohub.endpoint;

/**
 * 「咕咕星系」统一页面构建器（阶段三）。
 * <p>
 * 产出完全自包含的静态 HTML（内联 CSS/JS，零外部依赖）：
 * <ul>
 *   <li>站点目录 Tab：数据源 {@code GET /federation/sites}（站点卡片：标题/简介/计数/最近同步）；</li>
 *   <li>全网时间线 Tab：数据源 {@code GET /federation/items}（聚合卡片：站点徽标/类型/摘要/作者/时间/跳源站）。</li>
 * </ul>
 * 页面本体是纯静态壳，全部站点/条目数据由 JS 拉取 JSON 后经 <b>textContent</b> 注入
 * （永不 innerHTML 拼接用户数据）——服务端构建时无任何用户数据插值，天然无 XSS 面。
 */
public final class GalaxyPageBuilder {

    private GalaxyPageBuilder() {}

    /** 构建统一页面 HTML（纯静态壳，无任何用户数据插值） */
    public static String build() {
        return """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>咕咕星系 · 联邦统一页面</title>
            <style>
            :root{--bg:#0b1020;--panel:#131b31;--panel2:#182238;--line:#24304f;
                  --txt:#e7edff;--dim:#8ea0c4;--acc:#6ea8ff;--acc2:#8b7bff;
                  --ok:#5fd39a;--warn:#e8b45a}
            *{box-sizing:border-box;margin:0;padding:0}
            body{background:radial-gradient(1200px 700px at 70% -10%,#1b2450 0%,var(--bg) 55%);
                 color:var(--txt);font:15px/1.65 -apple-system,"Segoe UI","Microsoft YaHei",sans-serif;
                 min-height:100vh}
            a{color:var(--acc);text-decoration:none}
            a:hover{text-decoration:underline}
            .wrap{max-width:1080px;margin:0 auto;padding:28px 20px 60px}
            header{display:flex;align-items:baseline;gap:14px;flex-wrap:wrap;margin-bottom:6px}
            header h1{font-size:26px;font-weight:700;letter-spacing:.5px}
            header h1 .star{color:var(--acc2)}
            header .sub{color:var(--dim);font-size:13px}
            .tabs{display:flex;gap:8px;margin:18px 0 22px;border-bottom:1px solid var(--line)}
            .tab{padding:10px 18px;cursor:pointer;color:var(--dim);font-size:15px;
                 border-bottom:2px solid transparent;margin-bottom:-1px;user-select:none}
            .tab.on{color:var(--txt);border-bottom-color:var(--acc);font-weight:600}
            .grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(300px,1fr));gap:14px}
            .card{background:var(--panel);border:1px solid var(--line);border-radius:12px;
                  padding:16px 18px;transition:border-color .15s}
            .card:hover{border-color:var(--acc)}
            .card h3{font-size:16px;margin-bottom:2px}
            .card .sub{color:var(--dim);font-size:13px;margin-bottom:8px}
            .card .desc{color:var(--dim);font-size:13px;margin-bottom:10px;
                        display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;
                        overflow:hidden}
            .badge{display:inline-block;font-size:11px;padding:2px 8px;border-radius:20px;
                   border:1px solid var(--line);color:var(--dim);margin-right:6px}
            .badge.k-note{color:var(--acc);border-color:#2c4a7a}
            .badge.k-topic{color:var(--acc2);border-color:#443a7a}
            .badge.k-site{color:var(--ok);border-color:#2b5c46}
            .meta{color:var(--dim);font-size:12px;margin-top:10px;display:flex;gap:12px;flex-wrap:wrap}
            .meta .err{color:var(--warn)}
            .item{border-left:3px solid var(--acc);padding:14px 16px}
            .item.k-topic{border-left-color:var(--acc2)}
            .item .top{display:flex;gap:8px;align-items:center;margin-bottom:6px;flex-wrap:wrap}
            .item .site-tag{color:var(--acc);font-size:12px}
            .item h4{font-size:15px;font-weight:600;display:inline}
            .item .exc{color:var(--dim);font-size:13px;margin:4px 0 8px;
                       display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
            .toolbar{display:flex;gap:10px;align-items:center;margin-bottom:14px;flex-wrap:wrap}
            .toolbar select,.toolbar button{background:var(--panel2);border:1px solid var(--line);
                 color:var(--txt);border-radius:8px;padding:6px 12px;font-size:13px;cursor:pointer}
            .toolbar button:hover{border-color:var(--acc)}
            .empty{color:var(--dim);text-align:center;padding:48px 0}
            .state{color:var(--dim);font-size:13px;padding:24px 0;text-align:center}
            footer{margin-top:34px;color:var(--dim);font-size:12px;text-align:center}
            .hidden{display:none}
            </style>
            </head>
            <body>
            <div class="wrap">
            <header>
              <h1><span class="star">✦</span> 咕咕星系</h1>
              <span class="sub">联邦统一页面 · 站点目录 / 全网时间线</span>
            </header>

            <div class="tabs">
              <div class="tab on" id="tab-sites" data-t="sites">站点目录</div>
              <div class="tab" id="tab-items" data-t="items">全网时间线</div>
            </div>

            <section id="view-sites">
              <div class="toolbar"><button id="btn-reload">↻ 刷新</button>
                <span class="state" id="sites-state">加载中…</span></div>
              <div class="grid" id="sites-grid"></div>
              <div class="empty hidden" id="sites-empty">还没有站点接入 —— 站长可在各站调用 register 接口登记</div>
            </section>

            <section id="view-items" class="hidden">
              <div class="toolbar">
                <select id="kind-sel">
                  <option value="">全部类型</option>
                  <option value="note">咕咕</option>
                  <option value="topic">话题</option>
                </select>
                <button id="btn-more" class="hidden">加载更多</button>
                <span class="state" id="items-state"></span>
              </div>
              <div id="items-list"></div>
              <div class="empty hidden" id="items-empty">全网还没有聚合内容 —— 站点登记后触发一次同步</div>
            </section>

            <footer>咕咕星系 · plugin-goo-hub · 数据来自各联邦站点公开接口</footer>
            </div>

            <script>
            var API = '/apis/hub.api.goo.themenets.com/v1alpha1';
            var sitesMap = {};

            function $(id){return document.getElementById(id)}
            function escNode(el, txt){el.textContent = txt == null ? '' : String(txt)}
            function fmtTime(iso){try{return new Date(iso).toLocaleString()}catch(e){return iso||''}}
            function kindName(k){return k === 'topic' ? '话题' : (k === 'note' ? '咕咕' : k)}

            function showTab(name){
              document.querySelectorAll('.tab').forEach(function(t){
                t.classList.toggle('on', t.dataset.t === name)});
              $('view-sites').classList.toggle('hidden', name !== 'sites');
              $('view-items').classList.toggle('hidden', name !== 'items');
              if(name === 'items' && !$('items-list').children.length) loadItems(1);
            }
            document.querySelectorAll('.tab').forEach(function(t){
              t.addEventListener('click', function(){showTab(t.dataset.t)})});

            function loadSites(){
              $('sites-state').textContent = '加载中…';
              fetch(API + '/federation/sites', {cache:'no-store'})
                .then(function(r){return r.json()})
                .then(function(list){
                  sitesMap = {};
                  var grid = $('sites-grid');
                  grid.innerHTML = '';
                  $('sites-empty').classList.toggle('hidden', list.length > 0);
                  $('sites-state').textContent = list.length ? ('共 ' + list.length + ' 个站点') : '';
                  list.forEach(function(s){
                    if(s && s.url) sitesMap[s.name] = s.title || s.url;
                    var c = document.createElement('div'); c.className = 'card';
                    var h3 = document.createElement('h3');
                    var a = document.createElement('a');
                    a.target = '_blank'; a.rel = 'noopener';
                    escNode(a, s.title || s.url); a.href = s.url || '#';
                    h3.appendChild(a); c.appendChild(h3);
                    if(s.subtitle){var sub=document.createElement('div');sub.className='sub';
                      escNode(sub,s.subtitle);c.appendChild(sub);}
                    if(s.description){var d=document.createElement('div');d.className='desc';
                      escNode(d,s.description);c.appendChild(d);}
                    var badges = document.createElement('div');
                    (s.kinds || []).forEach(function(k){
                      var b=document.createElement('span');b.className='badge k-'+k;
                      escNode(b,kindName(k));badges.appendChild(b);});
                    c.appendChild(badges);
                    var meta=document.createElement('div');meta.className='meta';
                    var cnt=document.createElement('span');
                    escNode(cnt,'咕咕 '+(s.noteCount==null?'-':s.noteCount)+' · 话题 '
                                  +(s.topicCount==null?'-':s.topicCount));
                    meta.appendChild(cnt);
                    if(s.lastSyncAt){var ls=document.createElement('span');
                      escNode(ls,'同步 '+fmtTime(s.lastSyncAt));meta.appendChild(ls);}
                    if(s.lastError){var er=document.createElement('span');er.className='err';
                      escNode(er,s.lastError);meta.appendChild(er);}
                    c.appendChild(meta);
                    grid.appendChild(c);
                  });
                  $('sites-state').textContent = list.length ? ('共 ' + list.length + ' 个站点') : '';
                })
                .catch(function(){ $('sites-state').textContent = '加载失败，请刷新重试'; });
            }

            function loadItems(page){
              var kind = $('kind-sel').value;
              $('items-state').textContent = '加载中…';
              fetch(API + '/federation/items?page=' + page + '&size=20' +
                    (kind ? '&kind=' + kind : ''), {cache:'no-store'})
                .then(function(r){return r.json()})
                .then(function(res){
                  var list = $('items-list');
                  $('items-empty').classList.toggle('hidden', page > 1 || res.items.length > 0);
                  $('items-state').textContent = res.items.length ? '' : '本页没有内容';
                  res.items.forEach(function(it){
                    var c = document.createElement('div');
                    c.className = 'card item ' + (it.kind === 'topic' ? 'k-topic' : '');
                    var top = document.createElement('div'); top.className = 'top';
                    var kb = document.createElement('span'); kb.className = 'badge k-' + it.kind;
                    escNode(kb, kindName(it.kind)); top.appendChild(kb);
                    var st = document.createElement('span'); st.className = 'site-tag';
                    escNode(st, sitesMap[it.siteName] || it.siteName || '');
                    top.appendChild(st);
                    if(it.title){var h4=document.createElement('h4');escNode(h4,it.title);top.appendChild(h4);}
                    c.appendChild(top);
                    if(it.excerpt){var ex=document.createElement('div');ex.className='exc';
                      escNode(ex,it.excerpt);c.appendChild(ex);}
                    var meta=document.createElement('div');meta.className='meta';
                    if(it.authorDisplay || it.authorName){var au=document.createElement('span');
                      escNode(au,it.authorDisplay || it.authorName);meta.appendChild(au);}
                    if(it.sourceCreatedAt){var tm=document.createElement('span');
                      escNode(tm,fmtTime(it.sourceCreatedAt));meta.appendChild(tm);}
                    var link=document.createElement('a');
                    link.target='_blank';link.rel='noopener';link.href=it.contentUrl||'#';
                    escNode(link,'打开源站 ↗');meta.appendChild(link);
                    c.appendChild(meta);
                    list.appendChild(c);
                  });
                  $('btn-more').classList.toggle('hidden', !res.hasMore);
                  $('btn-more').onclick = function(){loadItems(page + 1)};
                })
                .catch(function(){ $('items-state').textContent = '加载失败，请刷新重试'; });
            }

            $('kind-sel').addEventListener('change', function(){
              $('items-list').innerHTML = ''; loadItems(1)});
            $('btn-reload').addEventListener('click', loadSites);

            loadSites();
            </script>
            </body>
            </html>
            """;
    }
}
