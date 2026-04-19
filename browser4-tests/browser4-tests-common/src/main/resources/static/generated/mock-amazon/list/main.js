// 模拟产品数据
const listEl = document.getElementById("product-list");
// If server already rendered products, don't override
if (listEl && listEl.getAttribute("data-server-rendered") === "true" && listEl.children.length > 0) {
  // Skip client-side generation to preserve SSR content
  console.debug("Product list server-rendered; skipping client generation");
} else {
  const products = Array.from({ length: 40 }, (_, i) => ({
      id: i + 1,
      title: `Monitor ${i + 1}`,
      price: `$${(Math.random() * 300 + 50).toFixed(2)}`,
      image: `https://picsum.photos/seed/monitor${i}/200/140`
  }));

  // 渲染占位框或真实数据
  products.forEach((p, idx) => {
      const card = document.createElement("div");
      card.className = "product-card";
      card.dataset.id = p.id;

      if (idx < 8) {
          renderProduct(card, p);
      } else {
          card.innerHTML = `
      <div class="placeholder"></div>
      <div class="placeholder-text"></div>
      <div class="placeholder-text" style="width:40%"></div>
    `;
      }
      listEl && listEl.appendChild(card);
  });

  function renderProduct(card, product) {
      card.innerHTML = `
    <a href="../product/index.html?id=${product.id}" class="product-link" style="text-decoration:none;color:inherit;display:block;">
        <img src="${product.image}" alt="${product.title}" class="product-img">
        <div class="product-title">${product.title}</div>
    </a>
    <div class="product-price">${product.price}</div>
  `;
  }

  // IntersectionObserver 懒加载
  const observer = new IntersectionObserver(entries => {
      entries.forEach(entry => {
          if (entry.isIntersecting) {
              const card = entry.target;
              const id = card.dataset.id;
              if (id) {
                  const product = products.find(p => p.id === Number(id));
                  renderProduct(card, product);
                  observer.unobserve(card);
              }
          }
      });
  }, { rootMargin: "50px" });

  // 监听后续卡片（仅懒加载）
  document.querySelectorAll('.product-card').forEach(card => {
      if (Number(card.dataset.id) > 8) {
          observer.observe(card);
      }
  });

  /* ================= Autocomplete ================= */
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
          window.location.href = '../product/index.html';
      }

      function build(value){
          const v = value.trim().toLowerCase();
          if(!v){ hide(); return; }
          currentSuggestions = products.filter(p=>p.title.toLowerCase().includes(v)).slice(0,8);
          if(!currentSuggestions.length){ hide(); return; }
          list.innerHTML = currentSuggestions.map((p,i)=>
              `<div class="autocomplete-item" role="option" aria-selected="${i===currentIndex}" data-index="${i}" data-id="${p.id}"><span>${p.title}</span><small>${p.price}</small></div>`
          ).join('');
          list.style.display='block';
          input.setAttribute('aria-expanded','true');
          currentIndex = -1;
      }

      function showDefault(){
          currentSuggestions = products.slice(0,8);
          list.innerHTML = currentSuggestions.map((p,i)=>
              `<div class="autocomplete-item" role="option" aria-selected="false" data-index="${i}" data-id="${p.id}"><span>${p.title}</span><small>${p.price}</small></div>`
          ).join('');
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
          navigateProduct();
      });

      document.addEventListener('click', e=>{ if(!e.target.closest('.autocomplete-container')) hide(); });

      const searchBtn = document.querySelector('.search-btn');
      if(searchBtn){ searchBtn.addEventListener('click', ()=> navigateProduct()); }
  })();
}
