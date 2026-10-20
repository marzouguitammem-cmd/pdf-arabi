/*
 * Word editor engine (runs inside the app's WebView).
 * The document is a list of blocks (<p>, <table>, raw blocks) holding runs (<span>) whose Word
 * properties live in data-* attributes; the look is derived from them. Kotlin converts .docx to this
 * HTML and back, keeping everything the user did not touch.
 */
(function () {
  'use strict';

  var D = window.DOCINFO || {};
  var body = document.getElementById('body');
  var paper = document.getElementById('paper');
  var Bridge = window.Bridge || {
    onReady: function () {}, onState: function () {}, onChange: function () {},
    onResult: function () {}, onImage: function () {}, onLink: function () {}
  };

  var editable = false;
  var view = 'page';
  var lastRange = null;
  var selImg = null;
  var RUN_PROPS = ['b', 'i', 'u', 's', 'va', 'sz', 'f', 'c', 'hl'];
  var ZW = /[\u200B\u200D]/g;

  // ------------------------------------------------------------------ fonts & look

  var NASKH = ['traditional arabic', 'simplified arabic', 'arabic typesetting', 'sakkal', 'majalla', 'times', 'amiri',
    'scheherazade', 'lotus', 'badr', 'mitra', 'nazanin', 'naskh', 'arabic transparent', 'qalam', 'uthman', 'quran',
    'kfgqpc', 'georgia', 'garamond', 'cambria', 'book antiqua', 'palatino', 'serif'];
  var SANS = ['arial', 'tahoma', 'calibri', 'segoe', 'verdana', 'helvetica', 'cairo', 'tajawal', 'droid', 'dubai',
    'roboto', 'almarai', 'kufi', 'janna', 'hacen', 'open sans', 'noto sans', 'century gothic', 'trebuchet', 'aptos',
    'franklin', 'sans'];
  var RUQAA = ['ruqaa', 'ruqa', 'diwani', 'aref', 'hand', 'kids', 'comic', 'script', 'thuluth'];

  function stack(name) {
    if (!name) return null;
    var n = name.toLowerCase();
    var fb;
    if (n.indexOf('courier') >= 0 || n.indexOf('mono') >= 0 || n.indexOf('consolas') >= 0) fb = 'monospace';
    else if (RUQAA.some(function (k) { return n.indexOf(k) >= 0; })) fb = '"Aref Ruqaa", "Amiri", serif';
    else if (SANS.some(function (k) { return n.indexOf(k) >= 0; })) fb = 'sans-serif';
    else if (NASKH.some(function (k) { return n.indexOf(k) >= 0; })) fb = '"Amiri", serif';
    else fb = '"Amiri", serif';
    return '"' + name.replace(/["\\]/g, '') + '", ' + fb;
  }

  var HL = {
    yellow: '#ffff00', green: '#00ff00', cyan: '#00ffff', magenta: '#ff00ff', blue: '#0000ff', red: '#ff0000',
    darkBlue: '#000080', darkCyan: '#008080', darkGreen: '#008000', darkMagenta: '#800080', darkRed: '#800000',
    darkYellow: '#808000', darkGray: '#808080', lightGray: '#c0c0c0', black: '#000000', white: '#ffffff'
  };

  function isRun(n) {
    // runs carry no class; classed spans are page breaks or the inside of a text box / field preview
    return n && n.nodeType === 1 && n.tagName === 'SPAN' && !n.dataset.k && !n.className;
  }

  function styleRun(s) {
    var d = s.dataset, st = s.style;
    st.cssText = d.xs || '';
    if (d.b) st.fontWeight = d.b === '1' ? '700' : '400';
    if (d.i) st.fontStyle = d.i === '1' ? 'italic' : 'normal';
    var dec = [];
    if (d.u && d.u !== 'none') dec.push('underline');
    if (d.s === '1') dec.push('line-through');
    if (dec.length) {
      st.textDecorationLine = dec.join(' ');
      var u = d.u || '';
      if (u === 'double') st.textDecorationStyle = 'double';
      else if (u.indexOf('dot') === 0) st.textDecorationStyle = 'dotted';
      else if (u.indexOf('dash') === 0) st.textDecorationStyle = 'dashed';
      else if (u.indexOf('wav') === 0) st.textDecorationStyle = 'wavy';
    } else if (d.u === 'none' || d.s === '0') {
      st.textDecorationLine = 'none';
    }
    if (d.sz) st.fontSize = (parseInt(d.sz, 10) / 2) + 'pt';
    if (d.f) st.fontFamily = stack(d.f);
    if (d.c) st.color = '#' + d.c;
    if (d.hl) st.backgroundColor = HL[d.hl] || ('#' + d.hl);
  }

  function styleP(p) {
    var d = p.dataset, st = p.style;
    st.cssText = d.xs || '';
    var al = d.al || 'start';
    st.textAlign = al === 'center' ? 'center' : al === 'end' ? 'end' : al === 'justify' ? 'justify' : 'start';
    var ind = (d.ind || '0,0,0').split(',').map(Number);
    if (ind[0]) st.marginInlineStart = (ind[0] / 20) + 'pt';
    if (ind[1]) st.marginInlineEnd = (ind[1] / 20) + 'pt';
    if (d.label !== undefined && d.num) {
      st.setProperty('--hang', (ind[2] < 0 ? -ind[2] / 20 : 0) + 'pt');
      if (ind[2] > 0) st.textIndent = (ind[2] / 20) + 'pt';
    } else if (ind[2]) {
      st.textIndent = (ind[2] / 20) + 'pt';
    }
    if (d.ls) {
      var ls = d.ls.split(',');
      var v = Number(ls[0]);
      if (v > 0) {
        if (ls[1] === 'exact' || ls[1] === 'atLeast') st.lineHeight = (v / 20) + 'pt';
        else st.lineHeight = (v / 240 * 1.17).toFixed(3);
      }
    }
    if (d.sb) st.paddingTop = (Number(d.sb) / 20) + 'pt';
    if (d.sa) st.paddingBottom = (Number(d.sa) / 20) + 'pt';
  }

  function cssId(s) { return String(s).replace(/["\\]/g, '\\$&'); }

  function styleVars(s) {
    var v = '';
    if (s.sz) v += '--sz:' + (s.sz / 2) + 'pt;';
    if (s.szCs) v += '--szcs:' + (s.szCs / 2) + 'pt;';
    if (s.f) v += '--ff:' + stack(s.f) + ';';
    if (s.fCs) v += '--ffcs:' + stack(s.fCs) + ';';
    if (s.b != null) v += '--b:' + (s.b ? 700 : 400) + ';';
    if (s.bCs != null) v += '--bcs:' + (s.bCs ? 700 : 400) + ';';
    if (s.i != null) v += '--i:' + (s.i ? 'italic' : 'normal') + ';';
    if (s.iCs != null) v += '--ics:' + (s.iCs ? 'italic' : 'normal') + ';';
    if (s.c) v += '--c:#' + s.c + ';';
    return v;
  }

  function buildStyleCss() {
    var css = '';
    if (D.defaults) css += '#body,.hf{' + styleVars(D.defaults) + '}\n';
    var S = D.styles || {};
    Object.keys(S).forEach(function (id) {
      css += 'p[data-st="' + cssId(id) + '"],.pp[data-st="' + cssId(id) + '"]{' + styleVars(S[id]) + '}\n';
    });
    if (D.page) {
      var pg = D.page;
      css += '@page{size:' + pg.w + 'px ' + pg.h + 'px;margin:' + pg.mt + 'px ' + pg.mr + 'px ' + pg.mb + 'px ' + pg.ml + 'px;}\n';
    }
    if (D.strings && D.strings.pageBreak) {
      css += ':root{--pblabel:"' + D.strings.pageBreak.replace(/["\\]/g, '') + '";}\n';
    }
    var el = document.getElementById('docvars');
    if (!el) { el = document.createElement('style'); el.id = 'docvars'; document.head.appendChild(el); }
    el.textContent = css;
  }

  function styleAll(root) {
    var ps = (root || document).querySelectorAll('p, .pp');
    for (var i = 0; i < ps.length; i++) styleP(ps[i]);
    var ss = (root || document).querySelectorAll('span');
    for (var j = 0; j < ss.length; j++) if (isRun(ss[j])) styleRun(ss[j]);
    var tds = (root || document).querySelectorAll('td[data-bg]');
    for (var k = 0; k < tds.length; k++) tds[k].style.backgroundColor = '#' + tds[k].dataset.bg;
  }

  // ------------------------------------------------------------------ lists

  var ABJAD = 'أبجدهوزحطيكلمنسعفصقرشتثخذضظغ';
  var ALPHA = 'أبتثجحخدذرزسشصضطظعغفقكلمنهوي';

  function letters(n) {
    var s = '';
    while (n > 0) { n--; s = String.fromCharCode(97 + (n % 26)) + s; n = Math.floor(n / 26); }
    return s;
  }

  function roman(n) {
    var map = [[1000, 'm'], [900, 'cm'], [500, 'd'], [400, 'cd'], [100, 'c'], [90, 'xc'], [50, 'l'], [40, 'xl'], [10, 'x'], [9, 'ix'], [5, 'v'], [4, 'iv'], [1, 'i']];
    var s = '';
    for (var i = 0; i < map.length; i++) while (n >= map[i][0]) { s += map[i][1]; n -= map[i][0]; }
    return s;
  }

  function fmtNum(n, f) {
    switch (f) {
      case 'decimalZero': return n < 10 ? '0' + n : String(n);
      case 'lowerLetter': return letters(n);
      case 'upperLetter': return letters(n).toUpperCase();
      case 'lowerRoman': return roman(n);
      case 'upperRoman': return roman(n).toUpperCase();
      case 'arabicAbjad': return ABJAD.charAt((n - 1) % 28);
      case 'arabicAlpha': return ALPHA.charAt((n - 1) % 28);
      case 'hindiNumbers': return String(n).replace(/\d/g, function (d) { return '٠١٢٣٤٥٦٧٨٩'.charAt(+d); });
      case 'none': return '';
      default: return String(n);
    }
  }

  function numDef(num) {
    if (!num) return null;
    if (D.nums && D.nums[num]) return D.nums[num];
    var m = /^new-([a-z]+)-/.exec(num);
    if (m && D.newNums && D.newNums[m[1]]) return D.newNums[m[1]];
    return null;
  }

  function listKind(p) {
    var def = numDef(p.dataset.num);
    if (!def) return '';
    if (def.kind) return def.kind;
    var l = def.levels[0] || {};
    if (l.f === 'bullet') return 'bullet';
    if (l.f === 'arabicAbjad' || l.f === 'arabicAlpha') return 'abjad';
    return 'decimal';
  }

  function renumber() {
    var counters = {};
    var ps = body.querySelectorAll('p');
    for (var i = 0; i < ps.length; i++) {
      var p = ps[i];
      var num = p.dataset.num;
      var def = numDef(num);
      if (!def) {
        if (p.dataset.label !== undefined) { delete p.dataset.label; styleP(p); }
        continue;
      }
      var lvl = Math.max(0, Math.min(8, parseInt(p.dataset.lvl || '0', 10)));
      var key = def.abs != null ? 'a' + def.abs : num;
      var c = counters[key] || (counters[key] = []);
      for (var k = 0; k < lvl; k++) if (c[k] == null) c[k] = (def.levels[k] || {}).s || 1;
      c[lvl] = c[lvl] == null ? ((def.levels[lvl] || {}).s || 1) : c[lvl] + 1;
      c.length = lvl + 1;
      var L = def.levels[lvl] || def.levels[0] || { f: 'decimal', t: '%1.' };
      var label;
      if (L.f === 'bullet') label = L.t || '•';
      else label = (L.t || '%1.').replace(/%(\d)/g, function (m, d) {
        var ix = +d - 1;
        var v = c[ix] != null ? c[ix] : ((def.levels[ix] || {}).s || 1);
        return fmtNum(v, (def.levels[ix] || L).f);
      });
      if (p.dataset.label !== label) { p.dataset.label = label; styleP(p); }
    }
  }

  var renumberTimer = 0;
  function renumberSoon() {
    clearTimeout(renumberTimer);
    renumberTimer = setTimeout(renumber, 120);
  }

  // ------------------------------------------------------------------ selection

  document.addEventListener('selectionchange', function () {
    var s = window.getSelection();
    if (s.rangeCount) {
      var r = s.getRangeAt(0);
      if (body.contains(r.commonAncestorContainer)) {
        lastRange = r.cloneRange();
        stateSoon();
      }
    }
  });

  function range() {
    var s = window.getSelection();
    if (s.rangeCount) {
      var r = s.getRangeAt(0);
      if (body.contains(r.commonAncestorContainer)) return r;
    }
    return lastRange;
  }

  function setRange(r) {
    var s = window.getSelection();
    s.removeAllRanges();
    s.addRange(r);
    lastRange = r.cloneRange();
  }

  function caretAt(node, offset) {
    var r = document.createRange();
    r.setStart(node, offset);
    r.collapse(true);
    setRange(r);
  }

  /** Focuses the editor keeping the user's selection (the live one if any, else the last known one). */
  function focusBody() {
    var s = window.getSelection();
    var live = null;
    if (s.rangeCount) {
      var r = s.getRangeAt(0);
      if (body.contains(r.commonAncestorContainer)) live = r.cloneRange();
    }
    var keep = live || lastRange;
    if (document.activeElement !== body) body.focus({ preventScroll: true });
    if (keep) {
      try { setRange(keep); } catch (e) { /* stale */ }
    }
  }

  function closestP(n) {
    while (n && n !== body) {
      if (n.nodeType === 1 && n.tagName === 'P') return n;
      n = n.parentNode;
    }
    return null;
  }

  function closestTag(n, tag) {
    while (n && n !== body) {
      if (n.nodeType === 1 && n.tagName === tag) return n;
      n = n.parentNode;
    }
    return null;
  }

  function runOf(n) {
    while (n && n !== body) {
      if (isRun(n)) return n;
      if (n.nodeType === 1 && n.tagName === 'P') return null;
      n = n.parentNode;
    }
    return null;
  }

  function endsAtStartOf(r, p) {
    var t = document.createRange();
    t.setStart(p, 0);
    try { t.setEnd(r.endContainer, r.endOffset); } catch (e) { return false; }
    return t.toString().replace(ZW, '') === '' && !t.cloneContents().querySelector('img');
  }

  function parasInRange(r) {
    var start = closestP(r.startContainer);
    if (r.collapsed) return start ? [start] : [];
    var out = [];
    var ps = body.querySelectorAll('p');
    for (var i = 0; i < ps.length; i++) {
      var p = ps[i];
      if (!r.intersectsNode(p)) continue;
      if (p !== start && closestP(r.endContainer) === p && endsAtStartOf(r, p)) continue;
      out.push(p);
    }
    if (!out.length && start) out.push(start);
    return out;
  }

  // ------------------------------------------------------------------ undo

  var undoStack = [], redoStack = [];
  var lastPush = 0, lastType = '';

  function pathOf(n) {
    var p = [];
    while (n && n !== body) {
      var par = n.parentNode;
      if (!par) return null;
      p.unshift(Array.prototype.indexOf.call(par.childNodes, n));
      n = par;
    }
    return n === body ? p : null;
  }

  function nodeAt(path) {
    var n = body;
    for (var i = 0; i < path.length; i++) {
      if (!n.childNodes[path[i]]) return null;
      n = n.childNodes[path[i]];
    }
    return n;
  }

  function snap() {
    var r = range(), s = null;
    if (r) s = { sp: pathOf(r.startContainer), so: r.startOffset, ep: pathOf(r.endContainer), eo: r.endOffset };
    return { h: body.innerHTML, s: s };
  }

  function restoreSnap(st) {
    body.innerHTML = st.h;
    selImg = null;
    if (st.s && st.s.sp && st.s.ep) {
      var a = nodeAt(st.s.sp), b = nodeAt(st.s.ep);
      if (a && b) {
        try {
          var r = document.createRange();
          r.setStart(a, Math.min(st.s.so, a.nodeType === 3 ? a.length : a.childNodes.length));
          r.setEnd(b, Math.min(st.s.eo, b.nodeType === 3 ? b.length : b.childNodes.length));
          setRange(r);
        } catch (e) { /* ignore */ }
      }
    }
  }

  function pushUndo() {
    undoStack.push(snap());
    if (undoStack.length > 120) undoStack.shift();
    redoStack.length = 0;
    lastPush = Date.now();
  }

  function undo() {
    if (!undoStack.length) return;
    redoStack.push(snap());
    restoreSnap(undoStack.pop());
    afterChange(true);
  }

  function redo() {
    if (!redoStack.length) return;
    undoStack.push(snap());
    restoreSnap(redoStack.pop());
    afterChange(true);
  }

  // ------------------------------------------------------------------ change notifications

  var stateTimer = 0, changeTimer = 0;

  function stateSoon() {
    clearTimeout(stateTimer);
    stateTimer = setTimeout(sendState, 90);
  }

  function afterChange(skipFix) {
    if (!skipFix) fixStructure();
    renumberSoon();
    clearTimeout(changeTimer);
    changeTimer = setTimeout(function () { Bridge.onChange(); }, 250);
    stateSoon();
  }

  function sendState() {
    try { Bridge.onState(JSON.stringify(state())); } catch (e) { /* ignore */ }
  }

  function styleFont(p, cs) {
    var S = (D.styles || {})[p ? p.dataset.st : ''] || {};
    var def = D.defaults || {};
    return cs ? (S.fCs || def.fCs || S.f || def.f || '') : (S.f || def.f || '');
  }

  function state() {
    var st = { u: undoStack.length > 0, r: redoStack.length > 0, ed: editable, img: !!selImg };
    var r = range();
    if (!r) return st;
    var node = r.startContainer;
    if (node.nodeType === 1 && node.childNodes[r.startOffset] && r.startOffset < node.childNodes.length) {
      var ch = node.childNodes[r.startOffset];
      if (ch.nodeType === 3 || isRun(ch)) node = ch;
    }
    var el = node.nodeType === 3 ? node.parentElement : node;
    if (!el || !body.contains(el)) return st;
    var p = closestP(el);
    var cs = window.getComputedStyle(el);
    var run = runOf(node);
    st.b = parseInt(cs.fontWeight, 10) >= 600;
    st.i = cs.fontStyle === 'italic';
    st.ul = !!(run && run.dataset.u && run.dataset.u !== 'none');
    st.s = !!(run && run.dataset.s === '1');
    st.va = (run && run.dataset.va) || '';
    st.sz = Math.round(parseFloat(cs.fontSize) * 0.75 * 2) / 2;
    var isCs = run ? run.dataset.cs === '1' || (!run.dataset.cs && p && p.getAttribute('dir') === 'rtl') : (p && p.getAttribute('dir') === 'rtl');
    st.f = (run && run.dataset.f) || styleFont(p, isCs);
    st.c = (run && run.dataset.c) || '';
    st.hl = (run && run.dataset.hl) || '';
    if (p) {
      st.al = p.dataset.al || 'start';
      st.dir = p.getAttribute('dir') || 'ltr';
      st.st = p.dataset.st || '';
      st.list = listKind(p);
      st.ls = p.dataset.ls || '';
      st.sb = p.dataset.sb || '0';
      st.sa = p.dataset.sa || '0';
    }
    var td = closestTag(el, 'TD');
    st.tbl = !!td;
    if (td) st.bg = td.dataset.bg || '';
    st.sel = !r.collapsed;
    return st;
  }

  // ------------------------------------------------------------------ structure fixes

  function newPara(like) {
    var n = document.createElement('p');
    if (like) {
      n.dataset.st = like.dataset.st || D.defStyle || '';
      if (like.getAttribute('dir')) n.setAttribute('dir', like.getAttribute('dir'));
      if (like.dataset.al) n.dataset.al = like.dataset.al;
    } else {
      n.dataset.st = D.defStyle || '';
      n.setAttribute('dir', D.rtl ? 'rtl' : 'ltr');
      n.dataset.al = 'start';
    }
    var S = (D.styles || {})[n.dataset.st] || {};
    if (S.ind) n.dataset.ind = S.ind;
    if (S.ls) n.dataset.ls = S.ls;
    if (S.sb) n.dataset.sb = S.sb;
    if (S.sa) n.dataset.sa = S.sa;
    n.innerHTML = '<br>';
    styleP(n);
    return n;
  }

  function fixStructure() {
    // Stray inline content directly in the body (or DIVs created by the browser) becomes paragraphs.
    var kids = Array.prototype.slice.call(body.childNodes);
    var pending = null;
    kids.forEach(function (n) {
      var block = n.nodeType === 1 && (n.tagName === 'P' || n.tagName === 'TABLE' || (n.classList && n.classList.contains('rawb')));
      if (block) { pending = null; return; }
      if (n.nodeType === 1 && n.tagName === 'DIV') {
        var p = newPara(null);
        p.innerHTML = '';
        while (n.firstChild) p.appendChild(n.firstChild);
        Array.prototype.forEach.call(n.attributes, function (a) { if (a.name !== 'style' && a.name !== 'class') p.setAttribute(a.name, a.value); });
        if (!p.firstChild) p.innerHTML = '<br>';
        body.replaceChild(p, n);
        styleP(p);
        pending = null;
        return;
      }
      if (n.nodeType === 3 && !n.data.trim()) { if (!pending) body.removeChild(n); else pending.appendChild(n); return; }
      if (!pending) {
        pending = newPara(null);
        pending.innerHTML = '';
        body.insertBefore(pending, n);
      }
      pending.appendChild(n);
    });
    if (!body.firstChild) body.appendChild(newPara(null));
    // Style spans the browser invents while merging paragraphs carry no Word meaning: unwrap them.
    var spans = body.querySelectorAll('span:not([data-k])');
    for (var i = 0; i < spans.length; i++) {
      var s = spans[i];
      if (s.attributes.length && !hasData(s) && s.getAttribute('style') !== null) {
        s.removeAttribute('style');
        if (!s.attributes.length) unwrap(s);
      }
    }
    var fonts = body.querySelectorAll('font');
    for (var j = 0; j < fonts.length; j++) unwrap(fonts[j]);
  }

  function hasData(el) {
    for (var i = 0; i < el.attributes.length; i++) if (el.attributes[i].name.indexOf('data-') === 0) return true;
    return false;
  }

  function unwrap(el) {
    var par = el.parentNode;
    if (!par) return;
    while (el.firstChild) par.insertBefore(el.firstChild, el);
    par.removeChild(el);
  }

  // ------------------------------------------------------------------ run formatting

  function textNodesIn(r) {
    var out = [];
    var root = r.commonAncestorContainer.nodeType === 3 ? r.commonAncestorContainer.parentNode : r.commonAncestorContainer;
    var w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
    var n;
    while ((n = w.nextNode())) {
      if (!n.data.length || !r.intersectsNode(n)) continue;
      if (insideSpecial(n)) continue;
      out.push(n);
    }
    return out;
  }

  function insideSpecial(n) {
    while (n && n !== body) {
      if (n.nodeType === 1 && n.dataset && (n.dataset.k === 'raw' || n.dataset.k === 'pb')) return true;
      if (n.nodeType === 1 && n.classList && n.classList.contains('rawb')) return true;
      n = n.parentNode;
    }
    return false;
  }

  function isolate(t) {
    var p = t.parentNode;
    if (isRun(p)) {
      if (p.childNodes.length === 1) return p;
      var before = p.cloneNode(false), after = p.cloneNode(false);
      while (p.firstChild !== t) before.appendChild(p.firstChild);
      while (t.nextSibling) after.appendChild(t.nextSibling);
      if (before.childNodes.length) p.parentNode.insertBefore(before, p);
      if (after.childNodes.length) p.parentNode.insertBefore(after, p.nextSibling);
      return p;
    }
    var s = document.createElement('span');
    var par = closestP(t);
    if (par && par.getAttribute('dir') === 'rtl' && /[\u0600-\u06FF]/.test(t.data)) s.dataset.cs = '1';
    p.insertBefore(s, t);
    s.appendChild(t);
    return s;
  }

  /** Splits text at the range edges and returns one run span per selected piece of text. */
  function selectedRuns(r) {
    var sc = r.startContainer, so = r.startOffset, ec = r.endContainer, eo = r.endOffset;
    if (ec.nodeType === 3 && eo > 0 && eo < ec.length) ec.splitText(eo);
    if (sc.nodeType === 3 && so > 0 && so < sc.length) {
      var nn = sc.splitText(so);
      if (ec === sc) { ec = nn; eo = eo - so; }
      sc = nn; so = 0;
    }
    var nr = document.createRange();
    try {
      nr.setStart(sc, so);
      nr.setEnd(ec, ec.nodeType === 3 ? Math.min(eo, ec.length) : eo);
    } catch (e) { return { runs: [], range: r }; }
    var texts = textNodesIn(nr).filter(function (t) {
      if (t === ec && eo === 0) return false;
      if (t === sc && so >= t.length) return false;
      return true;
    });
    var runs = texts.map(isolate);
    return { runs: runs, range: nr, texts: texts };
  }

  function reselect(texts) {
    if (!texts || !texts.length) return;
    var r = document.createRange();
    r.setStart(texts[0], 0);
    var last = texts[texts.length - 1];
    r.setEnd(last, last.length);
    setRange(r);
  }

  function setProp(span, prop, val) {
    if (val === null || val === undefined || val === '') delete span.dataset[prop];
    else span.dataset[prop] = String(val);
    styleRun(span);
  }

  /** A run where the next typed letters go, so a format chosen with nothing selected applies to them. */
  function caretRun(r) {
    var cur = runOf(r.startContainer);
    var s = cur ? cur.cloneNode(false) : document.createElement('span');
    if (!cur) {
      var p = closestP(r.startContainer);
      if (p && p.getAttribute('dir') === 'rtl') s.dataset.cs = '1';
    }
    s.textContent = '\u200B';
    r.insertNode(s);
    caretAt(s.firstChild, 1);
    return s;
  }

  function applyRuns(fn) {
    var r = range();
    if (!r) return;
    if (r.collapsed) {
      var s = caretRun(r);
      fn([s]);
      return;
    }
    var res = selectedRuns(r);
    fn(res.runs);
    reselect(res.texts);
  }

  function allTrue(runs, test) {
    return runs.length > 0 && runs.every(test);
  }

  function computedBold(s) { return parseInt(getComputedStyle(s).fontWeight, 10) >= 600; }
  function computedItalic(s) { return getComputedStyle(s).fontStyle === 'italic'; }

  var api = {};

  api.toggle = cmd(function (prop) {
    applyRuns(function (runs) {
      var on;
      if (prop === 'b') on = !allTrue(runs, computedBold);
      else if (prop === 'i') on = !allTrue(runs, computedItalic);
      else if (prop === 'u') on = !allTrue(runs, function (s) { return s.dataset.u && s.dataset.u !== 'none'; });
      else if (prop === 's') on = !allTrue(runs, function (s) { return s.dataset.s === '1'; });
      runs.forEach(function (s) {
        if (prop === 'u') setProp(s, 'u', on ? 'single' : (s.dataset.u ? 'none' : ''));
        else setProp(s, prop, on ? '1' : '0');
      });
    });
  });

  api.vertAlign = cmd(function (v) {
    applyRuns(function (runs) {
      var on = !allTrue(runs, function (s) { return s.dataset.va === v; });
      runs.forEach(function (s) { setProp(s, 'va', on ? v : ''); });
    });
  });

  api.setSize = cmd(function (pt) {
    applyRuns(function (runs) { runs.forEach(function (s) { setProp(s, 'sz', Math.round(pt * 2)); }); });
  });

  var SIZES = [8, 9, 10, 10.5, 11, 12, 13, 14, 15, 16, 18, 20, 22, 24, 26, 28, 32, 36, 40, 48, 56, 72];
  api.growSize = cmd(function (dir) {
    applyRuns(function (runs) {
      runs.forEach(function (s) {
        var cur = parseFloat(getComputedStyle(s).fontSize) * 0.75;
        var next = cur;
        if (dir > 0) { for (var i = 0; i < SIZES.length; i++) if (SIZES[i] > cur + 0.01) { next = SIZES[i]; break; } if (next === cur) next = cur + 4; }
        else { for (var j = SIZES.length - 1; j >= 0; j--) if (SIZES[j] < cur - 0.01) { next = SIZES[j]; break; } if (next === cur) next = Math.max(4, cur - 1); }
        setProp(s, 'sz', Math.round(next * 2));
      });
    });
  });

  api.setFont = cmd(function (name) {
    applyRuns(function (runs) { runs.forEach(function (s) { setProp(s, 'f', name); }); });
  });

  api.setColor = cmd(function (hex) {
    applyRuns(function (runs) { runs.forEach(function (s) { setProp(s, 'c', hex || ''); }); });
  });

  api.setHighlight = cmd(function (name) {
    applyRuns(function (runs) { runs.forEach(function (s) { setProp(s, 'hl', name || ''); }); });
  });

  api.clearFormat = cmd(function () {
    applyRuns(function (runs) {
      runs.forEach(function (s) {
        RUN_PROPS.forEach(function (k) { delete s.dataset[k]; });
        styleRun(s);
      });
    });
  });

  // ------------------------------------------------------------------ paragraph formatting

  function forParas(fn) {
    var r = range();
    if (!r) return;
    parasInRange(r).forEach(function (p) { fn(p); styleP(p); });
  }

  api.setAlign = cmd(function (al) { forParas(function (p) { p.dataset.al = al; }); });

  api.setDir = cmd(function (dir) {
    forParas(function (p) {
      p.setAttribute('dir', dir);
      var runs = p.querySelectorAll('span');
      for (var i = 0; i < runs.length; i++) {
        if (!isRun(runs[i])) continue;
        var arabic = /[\u0600-\u06FF]/.test(runs[i].textContent);
        if (dir === 'rtl' && arabic) runs[i].dataset.cs = '1';
      }
    });
    var r = range();
    if (r) {
      var t = closestTag(r.startContainer, 'TABLE');
      if (t && t.dataset.new) t.setAttribute('dir', dir);
    }
  });

  api.setLineSpacing = cmd(function (mult) {
    forParas(function (p) { p.dataset.ls = Math.round(mult * 240) + ',auto'; });
  });

  api.setSpacing = cmd(function (which, pt) {
    forParas(function (p) { p.dataset[which === 'before' ? 'sb' : 'sa'] = String(Math.round(pt * 20)); });
  });

  function setInd(p, s, e, f) { p.dataset.ind = [Math.max(0, s), Math.max(0, e), f].join(','); }

  api.indent = cmd(function (delta) {
    forParas(function (p) {
      var ind = (p.dataset.ind || '0,0,0').split(',').map(Number);
      if (p.dataset.num) {
        var lvl = Math.max(0, Math.min(8, parseInt(p.dataset.lvl || '0', 10) + delta));
        p.dataset.lvl = String(lvl);
        setInd(p, 720 * (lvl + 1), ind[1], -360);
      } else {
        setInd(p, ind[0] + 720 * delta, ind[1], ind[2]);
      }
    });
    renumber();
  });

  api.setStyle = cmd(function (id) {
    var S = (D.styles || {})[id] || {};
    forParas(function (p) {
      p.dataset.st = id;
      p.dataset.al = S.al || 'start';
      if (S.dir) p.setAttribute('dir', S.dir);
      if (!p.dataset.num) p.dataset.ind = S.ind || '0,0,0';
      if (S.ls) p.dataset.ls = S.ls; else delete p.dataset.ls;
      p.dataset.sb = S.sb || '0';
      p.dataset.sa = S.sa || '0';
      var runs = p.querySelectorAll('span');
      for (var i = 0; i < runs.length; i++) {
        if (!isRun(runs[i])) continue;
        ['b', 'i', 'sz', 'f', 'c'].forEach(function (k) { delete runs[i].dataset[k]; });
        styleRun(runs[i]);
      }
    });
  });

  var newListSeq = 0;

  function removeList(p) {
    delete p.dataset.num;
    delete p.dataset.lvl;
    delete p.dataset.label;
    var S = (D.styles || {})[p.dataset.st] || {};
    p.dataset.ind = S.ind || '0,0,0';
    styleP(p);
  }

  api.toggleList = cmd(function (kind) {
    var r = range();
    if (!r) return;
    var ps = parasInRange(r);
    if (!ps.length) return;
    var all = ps.every(function (p) { return listKind(p) === kind; });
    if (all) {
      ps.forEach(removeList);
    } else {
      var prev = ps[0].previousElementSibling;
      var num = (prev && prev.tagName === 'P' && listKind(prev) === kind) ? prev.dataset.num : 'new-' + kind + '-' + (++newListSeq) + '-' + Date.now();
      ps.forEach(function (p) {
        p.dataset.num = num;
        p.dataset.lvl = p.dataset.lvl || '0';
        var lvl = parseInt(p.dataset.lvl, 10) || 0;
        var ind = (p.dataset.ind || '0,0,0').split(',').map(Number);
        setInd(p, 720 * (lvl + 1), ind[1], -360);
        p.dataset.label = '';
        styleP(p);
      });
    }
    renumber();
  });

  // ------------------------------------------------------------------ inserting

  function insertNodeAtCaret(node) {
    var r = range();
    if (!r) return false;
    if (!r.collapsed) r.deleteContents();
    var p = closestP(r.startContainer);
    if (!p) {
      p = body.lastElementChild && body.lastElementChild.tagName === 'P' ? body.lastElementChild : null;
      if (!p) { p = newPara(null); body.appendChild(p); }
      r = document.createRange();
      r.selectNodeContents(p);
      r.collapse(false);
    }
    if (p.childNodes.length === 1 && p.firstChild.nodeName === 'BR') p.removeChild(p.firstChild), r.selectNodeContents(p), r.collapse(false);
    r.insertNode(node);
    var after = document.createRange();
    after.setStartAfter(node);
    after.collapse(true);
    setRange(after);
    return true;
  }

  api.insertText = cmd(function (txt) {
    var r = range();
    if (!r) return;
    if (!r.collapsed) r.deleteContents();
    document.execCommand('insertText', false, txt);
  });

  api.insertPageBreak = cmd(function () {
    var pb = document.createElement('span');
    pb.className = 'pb';
    pb.dataset.k = 'pb';
    pb.contentEditable = 'false';
    insertNodeAtCaret(pb);
  });

  api.insertLink = cmd(function (text, url) {
    var a = document.createElement('a');
    a.dataset.k = 'a';
    a.setAttribute('href', url);
    var s = document.createElement('span');
    s.textContent = text || url;
    if (/[\u0600-\u06FF]/.test(s.textContent)) s.dataset.cs = '1';
    a.appendChild(s);
    insertNodeAtCaret(a);
  });

  api.insertImage = cmd(function (src, media, w, h) {
    var img = document.createElement('img');
    img.dataset.k = 'img';
    img.dataset.m = media;
    img.dataset.new = '1';
    img.src = src;
    var max = contentWidth();
    if (w > max) { h = Math.round(h * max / w); w = Math.round(max); }
    img.style.width = w + 'px';
    img.style.height = h + 'px';
    insertNodeAtCaret(img);
  });

  function contentWidth() {
    var pg = D.page || { w: 794, ml: 96, mr: 96 };
    return Math.max(100, pg.w - pg.ml - pg.mr);
  }

  // ------------------------------------------------------------------ images

  function selectImage(img) {
    if (selImg && selImg !== img) selImg.classList.remove('sel');
    selImg = img;
    if (img) img.classList.add('sel');
    Bridge.onImage(img ? '1' : '0');
    stateSoon();
  }

  body.addEventListener('click', function (e) {
    var t = e.target;
    if (t && t.tagName === 'IMG' && t.dataset.k === 'img' && editable) {
      selectImage(t);
      var r = document.createRange();
      r.setStartAfter(t);
      r.collapse(true);
      setRange(r);
      return;
    }
    if (selImg) selectImage(null);
    var a = closestTag(t, 'A');
    if (a && !editable && a.getAttribute('href')) {
      e.preventDefault();
      Bridge.onLink(a.getAttribute('href'));
    }
  });

  api.imageWidth = cmd(function (fraction) {
    if (!selImg) return;
    var nw = selImg.naturalWidth || selImg.width || 1, nh = selImg.naturalHeight || selImg.height || 1;
    var cw = parseFloat(selImg.style.width) || selImg.width || nw;
    var ch = parseFloat(selImg.style.height) || selImg.height || nh;
    var ratio = ch / cw || nh / nw;
    var w = Math.round(contentWidth() * fraction);
    selImg.style.width = w + 'px';
    selImg.style.height = Math.round(w * ratio) + 'px';
  });

  api.imageDelete = cmd(function () {
    if (!selImg) return;
    var img = selImg;
    selectImage(null);
    img.parentNode.removeChild(img);
  });

  // ------------------------------------------------------------------ tables

  function curCell() {
    var r = range();
    return r ? closestTag(r.startContainer, 'TD') : null;
  }

  function grid(table) {
    var rows = Array.prototype.slice.call(table.rows);
    var g = [];
    rows.forEach(function (tr, ri) {
      g[ri] = g[ri] || [];
      var c = 0;
      Array.prototype.forEach.call(tr.cells, function (td) {
        while (g[ri][c]) c++;
        var cs = td.colSpan || 1, rs = td.rowSpan || 1;
        for (var a = 0; a < rs; a++) {
          g[ri + a] = g[ri + a] || [];
          for (var b = 0; b < cs; b++) g[ri + a][c + b] = td;
        }
        td._col = c;
        td._row = ri;
        c += cs;
      });
    });
    return g;
  }

  function emptyCellLike(td) {
    var n = td.cloneNode(false);
    n.removeAttribute('rowspan');
    n.removeAttribute('colspan');
    var first = td.querySelector('p');
    var p = newPara(first);
    n.appendChild(p);
    return n;
  }

  function newTablePara(like) {
    var p = newPara(like);
    p.dataset.sa = '0';
    p.dataset.sb = '0';
    p.dataset.ls = '240,auto';
    styleP(p);
    return p;
  }

  api.insertTable = cmd(function (rows, cols) {
    var r = range();
    var p = r ? closestP(r.startContainer) : null;
    var dir = p ? (p.getAttribute('dir') || 'ltr') : (D.rtl ? 'rtl' : 'ltr');
    var t = document.createElement('table');
    t.className = 'tbl';
    t.setAttribute('dir', dir);
    t.dataset.new = '1';
    var tb = document.createElement('tbody');
    t.appendChild(tb);
    var cw = Math.floor(contentWidth() / cols);
    for (var i = 0; i < rows; i++) {
      var tr = document.createElement('tr');
      for (var j = 0; j < cols; j++) {
        var td = document.createElement('td');
        td.style.border = '0.5pt solid #000';
        td.style.width = cw + 'px';
        td.appendChild(newTablePara(p));
        tr.appendChild(td);
      }
      tb.appendChild(tr);
    }
    var container = p ? p.parentNode : body;
    if (p && p.textContent.replace(ZW, '') === '' && !p.querySelector('img,.raw:not(.bm)')) {
      container.replaceChild(t, p);
    } else if (p) {
      container.insertBefore(t, p.nextSibling);
    } else {
      body.appendChild(t);
    }
    var nx = t.nextElementSibling;
    if (!nx || nx.tagName !== 'P') container.insertBefore(newPara(p), t.nextSibling);
    var firstP = t.querySelector('td p');
    if (firstP) caretAt(firstP, 0);
  });

  api.tableRow = cmd(function (after) {
    var td = curCell();
    if (!td) return;
    var table = closestTag(td, 'TABLE');
    var g = grid(table);
    var tr = td.parentNode;
    var ri = Array.prototype.indexOf.call(table.rows, tr);
    var boundary = after ? ri + 1 : ri;
    var refRow = g[after ? ri : ri] || [];
    var nr = tr.cloneNode(false);
    var seen = [];
    for (var c = 0; c < refRow.length; c++) {
      var cell = refRow[c];
      if (!cell || seen.indexOf(cell) >= 0) continue;
      seen.push(cell);
      var startRow = cell._row, endRow = cell._row + (cell.rowSpan || 1) - 1;
      if (startRow < boundary && endRow >= boundary) {
        cell.rowSpan = (cell.rowSpan || 1) + 1;
      } else {
        var n = emptyCellLike(cell);
        if ((cell.colSpan || 1) > 1) n.colSpan = cell.colSpan;
        nr.appendChild(n);
      }
    }
    var refTr = table.rows[boundary];
    if (refTr) refTr.parentNode.insertBefore(nr, refTr);
    else tr.parentNode.appendChild(nr);
    var p = nr.querySelector('p');
    if (p) caretAt(p, 0);
  });

  api.tableCol = cmd(function (after) {
    var td = curCell();
    if (!td) return;
    var table = closestTag(td, 'TABLE');
    var g = grid(table);
    var col = td._col + (after ? (td.colSpan || 1) - 1 : 0);
    var seen = [];
    for (var ri = 0; ri < g.length; ri++) {
      var cell = g[ri] && g[ri][col];
      if (!cell || seen.indexOf(cell) >= 0) continue;
      seen.push(cell);
      var cs = cell.colSpan || 1;
      var inside = after ? col < cell._col + cs - 1 : col > cell._col;
      if (cs > 1 && inside) { cell.colSpan = cs + 1; continue; }
      var n = emptyCellLike(cell);
      if ((cell.rowSpan || 1) > 1) n.rowSpan = cell.rowSpan;
      cell.parentNode.insertBefore(n, after ? cell.nextSibling : cell);
    }
  });

  api.tableDelRow = cmd(function () {
    var td = curCell();
    if (!td) return;
    var table = closestTag(td, 'TABLE');
    var g = grid(table);
    var tr = td.parentNode;
    var ri = Array.prototype.indexOf.call(table.rows, tr);
    if (table.rows.length <= 1) { removeTable(table); return; }
    var seen = [];
    (g[ri] || []).forEach(function (cell) {
      if (!cell || seen.indexOf(cell) >= 0) return;
      seen.push(cell);
      var rs = cell.rowSpan || 1;
      if (rs <= 1) return;
      if (cell._row < ri) { cell.rowSpan = rs - 1; return; }
      var next = table.rows[ri + 1];
      if (!next) return;
      cell.rowSpan = rs - 1;
      var ref = null;
      for (var k = 0; k < next.cells.length; k++) if (next.cells[k]._col > cell._col) { ref = next.cells[k]; break; }
      next.insertBefore(cell, ref);
    });
    tr.parentNode.removeChild(tr);
    var first = table.querySelector('td p');
    if (first) caretAt(first, 0);
  });

  api.tableDelCol = cmd(function () {
    var td = curCell();
    if (!td) return;
    var table = closestTag(td, 'TABLE');
    var g = grid(table);
    var col = td._col;
    var seen = [];
    for (var ri = 0; ri < g.length; ri++) {
      var cell = g[ri] && g[ri][col];
      if (!cell || seen.indexOf(cell) >= 0) continue;
      seen.push(cell);
      if ((cell.colSpan || 1) > 1) cell.colSpan = cell.colSpan - 1;
      else cell.parentNode.removeChild(cell);
    }
    Array.prototype.slice.call(table.rows).forEach(function (tr) { if (!tr.cells.length) tr.parentNode.removeChild(tr); });
    if (!table.querySelector('td')) { removeTable(table); return; }
    var first = table.querySelector('td p');
    if (first) caretAt(first, 0);
  });

  function removeTable(table) {
    var par = table.parentNode;
    var next = table.nextElementSibling;
    par.removeChild(table);
    if (!par.querySelector('p')) par.appendChild(newPara(null));
    var target = next && next.tagName === 'P' ? next : par.querySelector('p');
    if (target) caretAt(target, 0);
  }

  api.tableDelete = cmd(function () {
    var td = curCell();
    if (!td) return;
    removeTable(closestTag(td, 'TABLE'));
  });

  api.cellBg = cmd(function (hex) {
    var r = range();
    if (!r) return;
    var cells = [];
    var start = closestTag(r.startContainer, 'TD');
    if (!r.collapsed) {
      var all = body.querySelectorAll('td');
      for (var i = 0; i < all.length; i++) if (r.intersectsNode(all[i]) && !all[i].querySelector('td')) cells.push(all[i]);
    }
    if (!cells.length && start) cells.push(start);
    cells.forEach(function (td) {
      if (hex) { td.dataset.bg = hex; td.style.backgroundColor = '#' + hex; }
      else { td.dataset.bg = ''; td.style.backgroundColor = ''; }
    });
  });

  api.tableDir = cmd(function () {
    var td = curCell();
    if (!td) return;
    var t = closestTag(td, 'TABLE');
    t.setAttribute('dir', t.getAttribute('dir') === 'rtl' ? 'ltr' : 'rtl');
  });

  // ------------------------------------------------------------------ find & replace

  var matches = [], cur = -1;
  var hasHL = typeof CSS !== 'undefined' && CSS.highlights && typeof Highlight !== 'undefined';

  function normChar(c, mc) {
    var code = c.charCodeAt(0);
    if ((code >= 0x064B && code <= 0x065F) || code === 0x0670 || code === 0x0640 || code === 0x200B || code === 0x200D) return '';
    if (c === 'أ' || c === 'إ' || c === 'آ' || c === 'ٱ') return 'ا';
    if (c === 'ى') return 'ي';
    if (c === 'ة') return 'ه';
    if (code < 0x0600) {
      var d = c.normalize('NFD').replace(/[\u0300-\u036f]/g, '');
      return mc ? d : d.toLowerCase();
    }
    return c;
  }

  function norm(s, mc) {
    var out = '';
    for (var i = 0; i < s.length; i++) out += normChar(s.charAt(i), mc);
    return out.replace(/\s+/g, ' ');
  }

  function clearFind() {
    matches = [];
    cur = -1;
    if (hasHL) { CSS.highlights.delete('find'); CSS.highlights.delete('findcur'); }
  }

  function textNodesOf(p) {
    var out = [];
    var w = document.createTreeWalker(p, NodeFilter.SHOW_TEXT, null);
    var n;
    while ((n = w.nextNode())) {
      if (insideSpecial(n)) continue;
      if (closestP(n) !== p) continue;
      out.push(n);
    }
    return out;
  }

  api.find = function (q, mc) {
    clearFind();
    var nq = norm(q || '', !!mc).trim();
    if (!nq) { sendFind(); return 0; }
    var ps = body.querySelectorAll('p');
    for (var i = 0; i < ps.length; i++) {
      var nodes = textNodesOf(ps[i]);
      var text = '', map = [];
      nodes.forEach(function (n) {
        for (var k = 0; k < n.data.length; k++) {
          var ch = normChar(n.data.charAt(k), !!mc);
          if (/\s/.test(n.data.charAt(k))) ch = ' ';
          for (var z = 0; z < ch.length; z++) { text += ch.charAt(z); map.push([n, k]); }
        }
      });
      var from = 0, ix;
      while ((ix = text.indexOf(nq, from)) >= 0) {
        var a = map[ix], b = map[ix + nq.length - 1];
        var r = document.createRange();
        r.setStart(a[0], a[1]);
        r.setEnd(b[0], b[1] + 1);
        matches.push(r);
        from = ix + Math.max(1, nq.length);
      }
    }
    if (hasHL && matches.length) CSS.highlights.set('find', new Highlight(...matches));
    if (matches.length) {
      var startAt = 0;
      var lr = lastRange;
      if (lr) for (var m = 0; m < matches.length; m++) if (matches[m].compareBoundaryPoints(Range.START_TO_START, lr) >= 0) { startAt = m; break; }
      go(startAt);
    } else sendFind();
    return matches.length;
  };

  function sendFind() {
    try { Bridge.onResult('find', JSON.stringify({ n: matches.length, i: cur })); } catch (e) { /* ignore */ }
  }

  function go(i) {
    if (!matches.length) return;
    cur = (i % matches.length + matches.length) % matches.length;
    var r = matches[cur];
    if (hasHL) CSS.highlights.set('findcur', new Highlight(r));
    else setRange(r.cloneRange());
    var rect = r.getBoundingClientRect();
    var z = parseFloat(paper.style.zoom) || 1;
    var y = rect.top * (hasZoomInRects ? 1 : z);
    var target = window.scrollY + y - window.innerHeight * 0.3;
    window.scrollTo({ top: Math.max(0, target), behavior: 'smooth' });
    lastRange = r.cloneRange();
    sendFind();
  }

  var hasZoomInRects = true;

  api.findStep = function (d) { go(cur + d); };

  api.replaceCur = cmd(function (txt) {
    if (cur < 0 || !matches[cur]) return;
    var r = matches[cur];
    var start = r.startContainer, off = r.startOffset;
    r.deleteContents();
    if (start.nodeType === 3) start.insertData(Math.min(off, start.length), txt);
    else r.insertNode(document.createTextNode(txt));
  });

  api.replaceAll = cmd(function (q, txt, mc) {
    api.find(q, mc);
    for (var i = matches.length - 1; i >= 0; i--) {
      var r = matches[i];
      var start = r.startContainer, off = r.startOffset;
      r.deleteContents();
      if (start.nodeType === 3) start.insertData(Math.min(off, start.length), txt);
      else r.insertNode(document.createTextNode(txt));
    }
    var n = matches.length;
    clearFind();
    Bridge.onResult('replaced', String(n));
  });

  api.findClear = function () { clearFind(); };

  // ------------------------------------------------------------------ serialisation

  function runProps(span) {
    var o = {};
    if (!span) return o;
    if (span.dataset.x) o.x = +span.dataset.x;
    if (span.dataset.cs) o.cs = span.dataset.cs;
    RUN_PROPS.forEach(function (k) { if (span.dataset[k]) o[k] = span.dataset[k]; });
    return o;
  }

  function sameRun(a, b) {
    if (!a || !b || a.t !== 'r' || b.t !== 'r') return false;
    if (a.x !== b.x || a.cs !== b.cs) return false;
    for (var i = 0; i < RUN_PROPS.length; i++) if (a[RUN_PROPS[i]] !== b[RUN_PROPS[i]]) return false;
    return true;
  }

  function pushRun(out, props, txt) {
    txt = txt.replace(ZW, '');
    if (!txt) return;
    var r = {};
    for (var k in props) r[k] = props[k];
    r.t = 'r';
    r.txt = txt;
    var last = out[out.length - 1];
    if (sameRun(last, r)) last.txt += txt;
    else out.push(r);
  }

  function lastMeaningful(p) {
    var n = p.lastChild;
    while (n && n.nodeType === 3 && !n.data.replace(ZW, '').length) n = n.previousSibling;
    return n;
  }

  function serInline(el, props, out, p) {
    for (var n = el.firstChild; n; n = n.nextSibling) {
      if (n.nodeType === 3) { pushRun(out, props, n.data); continue; }
      if (n.nodeType !== 1) continue;
      var tag = n.tagName;
      var k = n.dataset ? n.dataset.k : null;
      if (tag === 'BR') {
        if (n === lastMeaningful(p) && !n.dataset.k) continue;
        out.push({ t: 'br' });
      } else if (k === 'pb') {
        out.push({ t: 'pb' });
      } else if (k === 'raw') {
        out.push({ t: 'raw', x: +n.dataset.x });
      } else if (tag === 'IMG' && k === 'img') {
        var w = parseFloat(n.style.width) || n.width, h = parseFloat(n.style.height) || n.height;
        var o = { t: 'img', w: Math.round(w), h: Math.round(h), m: n.dataset.m || '' };
        if (n.dataset.x) o.x = +n.dataset.x;
        if (n.dataset.new) o['new'] = 1;
        out.push(o);
      } else if (tag === 'A' && k === 'a') {
        var a = { t: 'a', href: n.getAttribute('href') || '', c: [] };
        if (n.dataset.x) a.x = +n.dataset.x;
        serInline(n, props, a.c, p);
        out.push(a);
      } else if (isRun(n)) {
        var merged = {};
        for (var key in props) merged[key] = props[key];
        var own = runProps(n);
        for (var key2 in own) merged[key2] = own[key2];
        serInline(n, merged, out, p);
      } else {
        var m = {};
        for (var key3 in props) m[key3] = props[key3];
        if (tag === 'B' || tag === 'STRONG') m.b = '1';
        else if (tag === 'I' || tag === 'EM') m.i = '1';
        else if (tag === 'U') m.u = 'single';
        else if (tag === 'S' || tag === 'STRIKE' || tag === 'DEL') m.s = '1';
        else if (tag === 'SUP') m.va = 'sup';
        else if (tag === 'SUB') m.va = 'sub';
        serInline(n, m, out, p);
      }
    }
  }

  function serP(p) {
    var d = p.dataset;
    var o = { t: 'p' };
    if (d.x) o.x = +d.x;
    o.st = d.st || D.defStyle || '';
    o.al = d.al || 'start';
    o.dir = p.getAttribute('dir') || 'ltr';
    if (d.num) { o.num = d.num; o.lvl = d.lvl || '0'; }
    o.ind = d.ind || '0,0,0';
    o.ls = d.ls || '';
    o.sb = d.sb || '0';
    o.sa = d.sa || '0';
    o.c = [];
    serInline(p, {}, o.c, p);
    return o;
  }

  function serTable(t) {
    var o = { t: 'tbl', dir: t.getAttribute('dir') || 'ltr', rows: [] };
    if (t.dataset.x) o.x = +t.dataset.x;
    Array.prototype.forEach.call(t.rows, function (tr) {
      if (closestTag(tr, 'TABLE') !== t) return;
      var row = { cells: [] };
      if (tr.dataset.x) row.x = +tr.dataset.x;
      Array.prototype.forEach.call(tr.cells, function (td) {
        var c = { cs: td.colSpan || 1, rs: td.rowSpan || 1, bg: td.dataset.bg || '', c: serBlocks(td) };
        if (td.dataset.x) c.x = +td.dataset.x;
        row.cells.push(c);
      });
      o.rows.push(row);
    });
    return o;
  }

  function serBlocks(container) {
    var out = [];
    for (var n = container.firstChild; n; n = n.nextSibling) {
      if (n.nodeType === 1) {
        if (n.tagName === 'P') out.push(serP(n));
        else if (n.tagName === 'TABLE') out.push(serTable(n));
        else if (n.classList.contains('rawb')) out.push({ t: 'rawb', x: +n.dataset.x });
        else if (n.tagName === 'DIV' || n.tagName === 'TBODY') out = out.concat(serBlocks(n));
        else {
          var tmp = { t: 'p', st: D.defStyle || '', al: 'start', dir: D.rtl ? 'rtl' : 'ltr', ind: '0,0,0', ls: '', sb: '0', sa: '0', c: [] };
          var wrap = document.createElement('p');
          wrap.appendChild(n.cloneNode(true));
          serInline(wrap, {}, tmp.c, wrap);
          out.push(tmp);
        }
      } else if (n.nodeType === 3 && n.data.replace(ZW, '').trim()) {
        out.push({ t: 'p', st: D.defStyle || '', al: 'start', dir: D.rtl ? 'rtl' : 'ltr', ind: '0,0,0', ls: '', sb: '0', sa: '0', c: [{ t: 'r', txt: n.data }] });
      }
    }
    return out;
  }

  api.serialize = function (token) {
    fixStructure();
    var data = JSON.stringify({ blocks: serBlocks(body) });
    Bridge.onResult(token, data);
  };

  api.html = function (token) { Bridge.onResult(token, body.innerHTML); };

  api.setHtml = function (h) {
    body.innerHTML = h;
    styleAll(body);
    renumber();
    undoStack.length = 0;
    redoStack.length = 0;
    stateSoon();
  };

  api.count = function () {
    var t = (body.innerText || '').replace(ZW, '');
    var words = (t.match(/[^\s.,;:!?،؛؟()«»"'\-–—…]+/g) || []).length;
    var chars = t.replace(/\n/g, '').length;
    var nospace = t.replace(/\s/g, '').length;
    var paras = Array.prototype.filter.call(body.querySelectorAll('p'), function (p) { return p.textContent.replace(ZW, '').trim(); }).length;
    var pages = 1;
    if (D.page && view === 'page') pages = Math.max(1, Math.ceil(paper.scrollHeight / D.page.h));
    return JSON.stringify({ w: words, c: chars, n: nospace, p: paras, pg: pages });
  };

  // ------------------------------------------------------------------ modes

  api.setEditable = function (on) {
    editable = !!on;
    body.contentEditable = editable ? 'true' : 'false';
    document.body.classList.toggle('reading', !editable);
    if (!editable) {
      if (selImg) selectImage(null);
      body.blur();
    } else {
      focusBody();
    }
    stateSoon();
  };

  function fit() {
    if (view === 'page' && D.page) {
      var avail = document.documentElement.clientWidth - 12;
      var z = Math.min(1.6, avail / D.page.w);
      paper.style.zoom = String(z);
    } else {
      paper.style.zoom = '';
    }
  }

  api.setView = function (v) {
    view = v === 'mobile' ? 'mobile' : 'page';
    document.body.classList.toggle('view-page', view === 'page');
    document.body.classList.toggle('view-mobile', view === 'mobile');
    fit();
  };

  api.setBottomInset = function (px) {
    document.documentElement.style.setProperty('--bar', Math.max(0, px) + 'px');
  };

  api.focus = function () { if (editable) focusBody(); };
  api.undo = function () { if (editable) undo(); };
  api.redo = function () { if (editable) redo(); };
  api.state = function () { return JSON.stringify(state()); };

  function cmd(fn) {
    return function () {
      if (!editable) return;
      var args = arguments;
      focusBody();
      pushUndo();
      try { fn.apply(null, args); } catch (e) { if (window.console) console.error(e); }
      afterChange();
    };
  }

  // ------------------------------------------------------------------ input handling

  function isEmptyP(p) {
    return p.textContent.replace(ZW, '').trim() === '' && !p.querySelector('img,.raw:not(.bm),.pb');
  }

  function atStartOf(r, p) {
    var t = document.createRange();
    t.setStart(p, 0);
    t.setEnd(r.startContainer, r.startOffset);
    return t.toString().replace(ZW, '') === '';
  }

  function pasteText(txt) {
    pushUndo();
    var lines = txt.replace(/\r\n?/g, '\n').split('\n');
    for (var i = 0; i < lines.length; i++) {
      if (i > 0) document.execCommand('insertParagraph');
      if (lines[i]) document.execCommand('insertText', false, lines[i]);
    }
    afterChange();
  }

  body.addEventListener('paste', function (e) {
    if (!editable) return;
    e.preventDefault();
    var txt = (e.clipboardData || window.clipboardData).getData('text/plain') || '';
    pasteText(txt);
  });

  body.addEventListener('drop', function (e) { e.preventDefault(); });

  body.addEventListener('beforeinput', function (e) {
    var t = e.inputType || '';
    if (t === 'historyUndo') { e.preventDefault(); undo(); return; }
    if (t === 'historyRedo') { e.preventDefault(); redo(); return; }
    if (!editable) { e.preventDefault(); return; }
    if (t === 'insertFromPaste' || t === 'insertFromDrop') {
      e.preventDefault();
      var dt = e.dataTransfer;
      var txt = dt ? dt.getData('text/plain') : '';
      if (txt) pasteText(txt);
      return;
    }
    var r = range();
    if (t === 'insertParagraph' && r) {
      var p = closestP(r.startContainer);
      if (p && p.dataset.num && isEmptyP(p)) {
        e.preventDefault();
        pushUndo();
        removeList(p);
        afterChange();
        return;
      }
    }
    if (t === 'deleteContentBackward' && r && r.collapsed) {
      var p2 = closestP(r.startContainer);
      if (p2 && p2.dataset.num && atStartOf(r, p2)) {
        e.preventDefault();
        pushUndo();
        removeList(p2);
        afterChange();
        return;
      }
    }
    if (selImg && (t.indexOf('delete') === 0)) {
      e.preventDefault();
      api.imageDelete();
      return;
    }
    var now = Date.now();
    if (now - lastPush > 900 || t === 'insertParagraph' || (t !== lastType && t.indexOf('delete') === 0)) pushUndo();
    lastPush = now;
    lastType = t;
  });

  body.addEventListener('input', function () { afterChange(); });

  body.addEventListener('keydown', function (e) {
    if (!editable) return;
    if (e.key === 'Tab') {
      e.preventDefault();
      document.execCommand('insertText', false, '\t');
    } else if ((e.ctrlKey || e.metaKey) && !e.altKey) {
      var k = e.key.toLowerCase();
      if (k === 'z') { e.preventDefault(); if (e.shiftKey) redo(); else undo(); }
      else if (k === 'y') { e.preventDefault(); redo(); }
      else if (k === 'b') { e.preventDefault(); api.toggle('b'); }
      else if (k === 'i') { e.preventDefault(); api.toggle('i'); }
      else if (k === 'u') { e.preventDefault(); api.toggle('u'); }
    }
  });

  window.addEventListener('resize', fit);

  // ------------------------------------------------------------------ start

  function init() {
    try { document.execCommand('defaultParagraphSeparator', false, 'p'); } catch (e) { /* ignore */ }
    buildStyleCss();
    styleAll(document);
    renumber();
    api.setView(D.view || 'page');
    api.setEditable(false);
    Bridge.onReady();
  }

  window.E = api;
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();
