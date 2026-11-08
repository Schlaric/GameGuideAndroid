(function () {
  "use strict";

  const key = "gameguide-reading-position:" + location.origin + location.pathname;
  const selector = "article p, article h1, article h2, article h3, article h4, article li, article blockquote, main p, main h1, main h2, main h3, main h4, main li, main blockquote";
  let saveTimer = null;
  let restoring = false;
  let userInteracted = false;
  let restoreAttempts = 0;

  function clean(value) {
    return (value || "").replace(/\s+/g, " ").trim();
  }

  function blocks() {
    return Array.from(document.querySelectorAll(selector))
      .filter(el => clean(el.innerText).length >= 35);
  }

  function currentBlock() {
    const list = blocks();
    if (!list.length) return null;
    const target = Math.min(180, Math.max(100, window.innerHeight * 0.28));
    return list.find(el => {
      const r = el.getBoundingClientRect();
      return r.bottom > target && r.top < window.innerHeight * 0.85;
    }) || null;
  }

  function makeAnchor(el) {
    const text = clean(el.innerText);
    const rect = el.getBoundingClientRect();
    const index = blocks().indexOf(el);
    const previous = index > 0 ? clean(blocks()[index - 1].innerText).slice(-70) : "";
    return {
      text: text.slice(0, 180),
      prefix: previous,
      tag: el.tagName.toLowerCase(),
      // Store the position relative to the viewport, not raw screen pixels.\n      viewportFraction: Math.max(-1, Math.min(1, rect.top / window.innerHeight)),\n      offset: Math.round(rect.top),
      savedAt: Date.now()
    };
  }

  function saveNow() {
    if (restoring || userInteracted === false && restoreAttempts > 0) return;
    const el = currentBlock();
    if (!el || !browser.storage || !browser.storage.local) return;
    browser.storage.local.set({ [key]: makeAnchor(el) });
  }

  function scheduleSave() {
    if (restoring) return;
    clearTimeout(saveTimer);
    saveTimer = setTimeout(saveNow, 450);
  }

  function findAnchor(saved) {
    const list = blocks();
    if (!list.length || !saved) return null;
    const wanted = clean(saved.text).slice(0, 90);
    if (wanted) {
      let best = null;
      let bestScore = 0;
      for (const el of list) {
        const text = clean(el.innerText);
        let score = 0;
        if (text.includes(wanted)) score = wanted.length;
        else {
          const short = wanted.slice(0, 45);
          if (short.length > 20 && text.includes(short)) score = short.length;
        }
        if (score > bestScore) {
          best = el;
          bestScore = score;
        }
      }
      if (best) return best;
    }
    // Never jump to an unrelated paragraph if the original text is absent.\n    return null;
  }

  function restore(saved) {
    if (!saved || userInteracted) return false;
    const el = findAnchor(saved);
    if (!el) return false;
    const desiredOffset = Number.isFinite(Number(saved.viewportFraction))\n      ? Number(saved.viewportFraction) * window.innerHeight\n      : Math.min(Number(saved.offset || 0), window.innerHeight * 0.6);\n    const top = window.scrollY + el.getBoundingClientRect().top - desiredOffset;
    window.scrollTo(0, Math.max(0, top));
    return true;
  }

  function markInteraction() {
    userInteracted = true;
    restoring = false;
    clearTimeout(saveTimer);
    scheduleSave();
  }

  ["wheel", "touchstart", "pointerdown", "keydown"].forEach(type => {
    window.addEventListener(type, markInteraction, { passive: true, capture: true });
  });
  window.addEventListener("scroll", scheduleSave, { passive: true });\n  // Reflow on fold/unfold changes paragraph geometry; wait for it to settle.\n  let resizeTimer = null;\n  window.addEventListener("resize", () => {\n    clearTimeout(resizeTimer);\n    resizeTimer = setTimeout(scheduleSave, 900);\n  }, { passive: true });
  window.addEventListener("pagehide", saveNow);
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "hidden") saveNow();
  });

  if (!browser.storage || !browser.storage.local) return;
  browser.storage.local.get(key).then(result => {
    const saved = result[key];
    if (!saved) {
      userInteracted = true;
      return;
    }
    restoring = true;
    const attempt = () => {
      if (userInteracted) {
        restoring = false;
        return;
      }
      restore(saved);
      restoreAttempts++;
      if (restoreAttempts < 12) {
        setTimeout(attempt, 700);
      } else {
        restoring = false;
        userInteracted = true;
      }
    };
    const start = () => {
      if (document.fonts && document.fonts.ready) {
        document.fonts.ready.then(() => setTimeout(attempt, 350));
      } else {
        setTimeout(attempt, 900);
      }
    };
    if (document.readyState === "complete") start();
    else window.addEventListener("load", start, { once: true });
  }).catch(() => { userInteracted = true; });
})();