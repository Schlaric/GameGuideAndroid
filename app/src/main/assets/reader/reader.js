(function () {
  "use strict";
  if (!/(^|\\.)ign\\.com$/i.test(location.hostname) || !location.pathname.startsWith("/wikis/")) return;

  const selectors = [
    "main [data-testid='article-body']",
    "[data-testid='article-body']",
    ".wiki-content",
    ".article-content",
    ".article-body",
    "#article-content",
    "article",
    "main"
  ];

  function applyReaderMode() {
    let article = null;
    for (const selector of selectors) {
      const candidate = document.querySelector(selector);
      if (candidate && candidate.innerText && candidate.innerText.trim().length > 250) {
        article = candidate;
        break;
      }
    }
    if (!article) return false;

    document.documentElement.classList.add("gameguide-reader-mode");
    article.classList.add("gameguide-reader-content");
    const styleId = "gameguide-reader-style";
    if (!document.getElementById(styleId)) {
      const style = document.createElement("style");
      style.id = styleId;
      style.textContent = `
        html.gameguide-reader-mode, html.gameguide-reader-mode body {
          background: #fff !important;
          color: #202124 !important;
        }
        html.gameguide-reader-mode body {
          margin: 0 !important;
          padding: 12px 16px 40px !important;
          font-family: Arial, sans-serif !important;
        }
        html.gameguide-reader-mode body > header,
        html.gameguide-reader-mode body > footer,
        html.gameguide-reader-mode header:not(.gameguide-reader-content),
        html.gameguide-reader-mode footer,
        html.gameguide-reader-mode aside,
        html.gameguide-reader-mode [role="complementary"],
        html.gameguide-reader-mode [data-testid*="header"],
        html.gameguide-reader-mode [data-testid*="footer"],
        html.gameguide-reader-mode [class*="ad-container"],
        html.gameguide-reader-mode [class*="AdContainer"],
        html.gameguide-reader-mode [id*="ad-container"],
        html.gameguide-reader-mode [class*="advert"],
        html.gameguide-reader-mode [id*="advert"],
        html.gameguide-reader-mode [class*="outbrain"],
        html.gameguide-reader-mode [class*="taboola"],
        html.gameguide-reader-mode [class*="newsletter"],
        html.gameguide-reader-mode [class*="recommend"],
        html.gameguide-reader-mode [class*="related-content"],
        html.gameguide-reader-mode [class*="social-share"],
        html.gameguide-reader-mode [class*="sticky"],
        html.gameguide-reader-mode [class*="modal"],
        html.gameguide-reader-mode [class*="overlay"] {
          display: none !important;
        }
        html.gameguide-reader-mode .gameguide-reader-content {
          display: block !important;
          visibility: visible !important;
          width: 100% !important;
          max-width: 820px !important;
          margin: 0 auto !important;
          padding: 0 !important;
          background: #fff !important;
          color: #202124 !important;
          font-family: Arial, sans-serif !important;
          font-size: 17px !important;
          line-height: 1.65 !important;
          box-shadow: none !important;
          border: 0 !important;
        }
        html.gameguide-reader-mode .gameguide-reader-content * {
          max-width: 100%;
          box-sizing: border-box;
        }
        html.gameguide-reader-mode .gameguide-reader-content p,
        html.gameguide-reader-mode .gameguide-reader-content li {
          line-height: 1.7 !important;
        }
        html.gameguide-reader-mode .gameguide-reader-content img,
        html.gameguide-reader-mode .gameguide-reader-content picture,
        html.gameguide-reader-mode .gameguide-reader-content video {
          height: auto !important;
          max-width: 100% !important;
        }
        html.gameguide-reader-mode .gameguide-reader-content a {
          color: #1558b0 !important;
          text-decoration: underline !important;
        }
        html.gameguide-reader-mode .gameguide-reader-content h1,
        html.gameguide-reader-mode .gameguide-reader-content h2,
        html.gameguide-reader-mode .gameguide-reader-content h3 {
          color: #17191d !important;
          line-height: 1.25 !important;
        }
      `;
      (document.head || document.documentElement).appendChild(style);
    }
    return true;
  }

  let attempts = 0;
  const timer = setInterval(() => {
    attempts++;
    if (applyReaderMode() || attempts >= 20) clearInterval(timer);
  }, 500);

  const observer = new MutationObserver(() => applyReaderMode());
  observer.observe(document.documentElement, { childList: true, subtree: true });
  setTimeout(() => observer.disconnect(), 15000);
})();