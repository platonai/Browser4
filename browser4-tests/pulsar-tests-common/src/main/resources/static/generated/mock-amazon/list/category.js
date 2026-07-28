// Category page dynamic behaviors using server-injected dataset
(function(){
  const products = (window.__PRODUCTS__ || []).map(p => ({...p}));
  const listEl = document.getElementById('product-list');
  if(!listEl) return;

  function renderProduct(card, product){
    if(!product) return;
    card.innerHTML = `
      <a href="${product.link}" class="product-link" style="text-decoration:none;color:inherit;display:block;">
        <img src="${product.image}" alt="${product.title}" class="product-img">
        <div class="product-title">${product.title}</div>
      </a>
      <div class="product-price">${product.price}</div>
    `;
    card.removeAttribute('data-lazy');
  }

  // Lazy load remaining product cards
  const observer = new IntersectionObserver(entries => {
    entries.forEach(entry => {
      if(entry.isIntersecting){
        const card = entry.target;
        const id = card.getAttribute('data-id');
        const p = products.find(x => String(x.id) === id);
        renderProduct(card, p);
        observer.unobserve(card);
      }
    });
  }, { rootMargin: '80px' });

  document.querySelectorAll('.product-card[data-lazy="true"]').forEach(card => observer.observe(card));

  /* ================= Autocomplete (mirrors main.js) ================= */
  (function initAutocomplete(){
    const input = document.getElementById('search-box');
    const list = document.getElementById('autocomplete-list');
    if(!input || !list) return;

    list.setAttribute('role','listbox');
    input.setAttribute('role','combobox');
    input.setAttribute('aria-autocomplete','list');
    input.setAttribute('aria-expanded','false');

    let currentIndex = -1;
    let currentSuggestions = [];

    function hide(){
      list.style.display='none';
      list.innerHTML='';
      currentIndex = -1;
      currentSuggestions = [];
      input.setAttribute('aria-expanded','false');
    }

    function navigateProduct(){
      if(currentSuggestions[currentIndex]){
        window.location.href = currentSuggestions[currentIndex].link;
      } else if (currentSuggestions.length){
        window.location.href = currentSuggestions[0].link;
      }
    }

    function build(value){
      const v = value.trim().toLowerCase();
      if(!v){ hide(); return; }
      currentSuggestions = products.filter(p=>String(p.title).toLowerCase().includes(v)).slice(0,8);
      if(!currentSuggestions.length){ hide(); return; }
      list.innerHTML = currentSuggestions.map((p,i)=>`<div class="autocomplete-item" role="option" aria-selected="${i===currentIndex}" data-index="${i}" data-id="${p.id}"><span>${p.title}</span><small>${p.price}</small></div>`).join('');
      list.style.display='block';
      input.setAttribute('aria-expanded','true');
      currentIndex = -1;
    }

    function showDefault(){
      currentSuggestions = products.slice(0,8);
      list.innerHTML = currentSuggestions.map((p,i)=>`<div class="autocomplete-item" role="option" aria-selected="false" data-index="${i}" data-id="${p.id}"><span>${p.title}</span><small>${p.price}</small></div>`).join('');
      list.style.display='block';
      input.setAttribute('aria-expanded','true');
      currentIndex = -1;
    }

    input.addEventListener('focus', ()=>{ !input.value.trim() ? showDefault() : build(input.value); });
    input.addEventListener('input', e=>{ !e.target.value.trim() ? showDefault() : build(e.target.value); });

    input.addEventListener('keydown', e=>{
      const listVisible = list.style.display !== 'none';
      if(!listVisible && e.key === 'ArrowDown') showDefault();
      if(listVisible){
        const items = list.querySelectorAll('.autocomplete-item');
        if(e.key === 'ArrowDown'){
          e.preventDefault();
          currentIndex = (currentIndex + 1) % items.length;
          updateActive(items);
        } else if(e.key === 'ArrowUp') {
          e.preventDefault();
          currentIndex = (currentIndex - 1 + items.length) % items.length;
          updateActive(items);
        } else if(e.key === 'Enter') {
          e.preventDefault();
          navigateProduct();
        } else if(e.key === 'Escape') hide();
      } else if(e.key === 'Enter' && input.value.trim()) {
        e.preventDefault();
        navigateProduct();
      }
    });

    function updateActive(items){
      items.forEach((el,idx)=>{
        el.classList.toggle('active', idx===currentIndex);
        el.setAttribute('aria-selected', String(idx===currentIndex));
      });
    }

    list.addEventListener('click', e=>{
      const item = e.target.closest('.autocomplete-item');
      if(!item) return;
      const idx = Number(item.getAttribute('data-index'));
      currentIndex = idx;
      navigateProduct();
    });

    document.addEventListener('click', e=>{ if(!e.target.closest('.autocomplete-container')) hide(); });

    const searchBtn = document.querySelector('.search-btn');
    if(searchBtn){ searchBtn.addEventListener('click', ()=> navigateProduct()); }
  })();
})();

