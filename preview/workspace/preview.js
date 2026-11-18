const $ = selector => document.querySelector(selector);
const paths = {
  archive: 'M3 11h13v3a6.5 6.5 0 0 1-13 0z M16 11.5h2a3 3 0 0 1 0 6h-2 M2 22h17',
  folder: 'M3 7V5h7l2 2h9v12H3z',
  search: 'M16 16l5 5 M18 10.5a7.5 7.5 0 1 1-15 0 7.5 7.5 0 1 1 15 0',
  structure: 'M4 3h6v6H4z M7 9v10h6 M7 13h6 M14 11h6v4h-6z M14 17h6v4h-6z',
  instructions: 'M3 4h18v16H3z M3 9h18 M7 13l2 2-2 2 M12 17h4',
  split: 'M3 4h18v16H3z M12 4v16',
  fold: 'm7 3 5 5 5-5 M7 20l5-5 5 5',
  method: 'm4 8 8-5 8 5-8 5z M4 8v8l8 5 8-5V8 M12 13v8',
  close: 'm6 6 12 12 M18 6 6 18'
};
const icon = name => '<svg aria-hidden="true" viewBox="0 0 24 24"><path d="'+paths[name]+'"/>'+(name==='archive'?'<path class="steam" d="M7 8c3-2-2-3 0-5 M12 8c3-2-2-3 0-5"/>':'')+'</svg>';
const esc = value => value.replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;');
const symbol = kind => '<span class="symbol '+kind+'" aria-hidden="true">'+({class:'C',method:'m',field:'f'})[kind]+'</span>';
const mobile = matchMedia('(max-width:760px)');
let samples, layout, selected='RouteRegistry', openFiles=['RouteRegistry','Route','Router'];
let line=14, method='register', view='bytecode', projectOpen, structureOpen, panelOpen, sideBySide;

function styledCode(lineText) {
  const pattern=/^ +|"(?:[^"\\]|\\.)*"|\b(?:package|import|public|private|final|class|static|new|return|void|boolean|int|this)\b|\b[A-Z]\w*|\b\w+(?=\()/g;
  let html='', end=0;
  for (const m of lineText.matchAll(pattern)) {
    const value=m[0];
    let style='kw';
    if(value.startsWith(' '))style='indent';
    else if(value.startsWith('"'))style='str';
    else if(/^[A-Z]/.test(value))style='type';
    else if(lineText[m.index+value.length]==='(')style='fn';
    html+=esc(lineText.slice(end,m.index))+'<span class="'+style+'">'+esc(value)+'</span>';end=m.index+value.length;
  }
  return html+esc(lineText.slice(end));
}
function tree(){
  const groups=['dev.atlas.core','dev.atlas.io'].map(pkg=>{
    const files=Object.keys(samples).filter(n=>samples[n].pkg===pkg);
    return '<details open><summary>'+icon('folder')+pkg+'</summary>'+files.map(n=>'<button class="file" data-file="'+n+'">'+symbol('class')+n+'.class</button>').join('')+'</details>';
  });
  $('#tree').innerHTML='<details open><summary>'+icon('archive')+'atlas-core.jar</summary>'+groups.join('')+'</details>';
}
function source(){
  const item=samples[selected], lines=item.source.split('\n');
  document.querySelectorAll('.file').forEach(b=>b.classList.toggle('current',b.dataset.file===selected));
  $('#tabs').innerHTML=openFiles.map(n=>'<button data-file="'+n+'" class="'+(n===selected?'current':'')+'">'+symbol('class')+n+'.class</button>').join('');
  $('.breadcrumb').innerHTML='atlas-core.jar　›　'+item.pkg+'　›　'+symbol('class')+' <b>'+selected+'</b>';
  $('#listing').innerHTML=lines.map((s,i)=>'<div class="line" data-row="'+(i+1)+'"><span class="num">'+(i+1)+'</span><span>'+styledCode(s)+'</span></div>').join('');
  const members=[];
  lines.forEach((s,i)=>{
    const field=s.match(/^    private final (.+) (\w+);$/);
    if(field)members.push('<button class="member" data-line="'+(i+1)+'">'+symbol('field')+field[2]+' <small>'+esc(field[1])+'</small></button>');
  });
  for(const [i,name] of item.methods){
    const params=lines[i-1].split('(')[1].split(')')[0];
    members.push('<button class="member" data-line="'+i+'">'+symbol('method')+name+'<small>('+esc(params)+')</small></button>');
  }
  $('#members').innerHTML='<div class="classLabel">'+symbol('class')+selected+'</div>'+members.join('');
  $('#memberCount').textContent=members.length;
  $('.methodPick select').innerHTML=item.methods.map(([,n])=>'<option value="'+n+'">'+n+'()</option>').join('');
  refreshSelection();
}
function refreshSelection(){
  document.querySelectorAll('.line').forEach(row=>row.classList.toggle('selected',+row.dataset.row===line));
  document.querySelectorAll('.member').forEach(b=>b.classList.toggle('current',+b.dataset.line===line));
  $('#statusPath').textContent=samples[selected].pkg+'  ›  '+selected;
  $('#lineStatus').textContent='Ln '+line;
  $('.methodPick select').value=method;
  inspect();
}
function inspect(){
  const item=samples[selected];
  document.querySelectorAll('[data-view]').forEach(b=>b.classList.toggle('current',b.dataset.view===view));
  $('.methodPick').hidden=view!=='bytecode';
  if(view==='details'){
    const fields=[['Class',selected],['Package',item.pkg],['Flags','public final'],['Superclass','java.lang.Object'],['Class file version','61.0 (Java 17)']];
    $('#inspections').innerHTML='<dl>'+fields.map(([k,v])=>'<dt>'+k+'</dt><dd>'+v+'</dd>').join('')+'</dl>';
    return;
  }
  const blocks=item.bytecode.split(/\n(?=  public )/);
  const block=blocks.find(s=>s.split('\n')[0].match(/([\w$]+)\([^()]*\);$/)?.[1]===method)||'';
  const rows=[...block.matchAll(/^\s*(\d+):\s+(\w+)([^\n]*)/gm)].map(([,offset,op,rest])=>{
    const [arg,comment='']=rest.split('//');
    return '<tr><td>'+offset+'</td><td class="opcode">'+op+'</td><td>'+esc(arg.trim())+'</td><td>'+esc(comment.trim())+'</td></tr>';
  });
  $('#inspections').innerHTML='<table><thead><tr><th>Offset</th><th>Instruction</th><th>Operand</th><th>Reference</th></tr></thead><tbody>'+rows.join('')+'</tbody></table>';
}
function goToLine(number,scroll){
  line=number;
  const preceding=samples[selected].methods.filter(([n])=>n<=line).at(-1);
  method=(preceding||samples[selected].methods[0])[1];
  refreshSelection();
  if(scroll)$('#listing .selected')?.scrollIntoView({block:'nearest'});
}
function openFile(file,target){
  const changed=selected!==file;
  selected=file;
  if(!openFiles.includes(file))openFiles.push(file);
  if(changed){line=1;method=samples[file].methods[0][1]}
  if(mobile.matches){projectOpen=false;structureOpen=false;panels()}
  source();
  if(target)goToLine(target,true);
  else if(changed)$('#listing').scrollTop=0;
}
function panels(){
  $('.sidebar').hidden=!projectOpen&&!structureOpen;
  $('.projectTree').hidden=!projectOpen;
  $('.structure').hidden=!structureOpen;
  $('.dock').hidden=!panelOpen;
  $('.desk').classList.toggle('sideBySide',sideBySide);
  for(const [id,active] of [['projectToggle',projectOpen],['structureToggle',structureOpen],['panelToggle',panelOpen]]){
    $('#'+id).classList.toggle('on',active);$('#'+id).setAttribute('aria-pressed',active);
  }
}
function useLayout(){
  layout=['code','solo','twin'].includes(location.hash.slice(1))?'code':'idea';
  document.body.dataset.layout=layout;
  document.querySelectorAll('nav a').forEach(a=>{if(a.hash==='#'+layout)a.setAttribute('aria-current','page');else a.removeAttribute('aria-current')});
  $('#treeTitle').textContent=layout==='idea'?'Project':'Explorer';
  $('#structureTitle').textContent=layout==='idea'?'Structure':'Outline';
  projectOpen=!mobile.matches;structureOpen=!mobile.matches;
  panelOpen=true;sideBySide=layout==='code';
  panels();
}
function searchResults(){
  const query=$('dialog input').value.trim().toLowerCase();
  const results=[];
  for(const [file,item] of Object.entries(samples)){
    if((item.pkg+'.'+file).toLowerCase().includes(query))results.push([file,0,file,item.pkg]);
    if(query)for(const [n,name] of item.methods){
      if((file+'.'+name).toLowerCase().includes(query))results.push([file,n,name+'()',file]);
    }
  }
  const exact = result => result[2].replace(/\(\)$/, '').toLowerCase() === query;
  results.sort((a, b) => Number(exact(b)) - Number(exact(a)));
  $('#results').innerHTML=results.map(([file,n,label,context])=>'<button data-file="'+file+'" data-target="'+n+'">'+symbol(n?'method':'class')+label+'<small>'+context+'</small></button>').join('')||'<p>No matching classes or methods</p>';
}
function findSymbol(){
  $('dialog input').value='';searchResults();$('dialog').showModal();$('dialog input').focus();
}
document.querySelectorAll('[data-icon]').forEach(i=>i.innerHTML=icon(i.dataset.icon));
async function init(){
  samples=await (await fetch('sample.json')).json();tree();source();useLayout();
  addEventListener('hashchange',useLayout);mobile.addEventListener('change',useLayout);
  document.addEventListener('click',e=>{
    const b=e.target.closest('button');
    if(!b){const row=e.target.closest('[data-row]');if(row)goToLine(+row.dataset.row,false);return}
    if(b.dataset.file){if($('dialog').open)$('dialog').close();openFile(b.dataset.file,+b.dataset.target);return}
    if(b.dataset.line){if(mobile.matches){projectOpen=false;structureOpen=false;panels()}goToLine(+b.dataset.line,true);return}
    if(b.dataset.view){view=b.dataset.view;inspect();return}
    switch(b.id){
      case 'projectToggle':projectOpen=!projectOpen;if(mobile.matches||!projectOpen)structureOpen=false;break;
      case 'structureToggle':structureOpen=!structureOpen;if(mobile.matches)projectOpen=false;break;
      case 'panelToggle':panelOpen=!panelOpen;break;
      case 'hidePanel':panelOpen=false;break;
      case 'split':sideBySide=!sideBySide;panelOpen=true;break;
      case 'collapse':document.querySelectorAll('#tree details details').forEach(d=>d.open=false);break;
      case 'find':case 'search':findSymbol();break;
      case 'dismiss':$('dialog').close();break;
    }
    panels();
  });
  $('.methodPick select').onchange=e=>goToLine(samples[selected].methods.find(([,n])=>n===e.target.value)[0],true);
  $('dialog input').addEventListener('input',searchResults);
  let pressedAt=null, lastTap=null;
  function clearTaps(){pressedAt=null;lastTap=null}
  window.addEventListener('blur',clearTaps);
  document.addEventListener('keyup',e=>{
    if(e.key!=='Shift'||pressedAt===null){clearTaps();return}
    const now=performance.now();
    const short=now-pressedAt<450;
    const twice=short&&lastTap!==null&&now-lastTap<450;
    pressedAt=null;lastTap=short&&!twice?now:null;
    if(twice&&!$('dialog').open)findSymbol();
  });
  document.addEventListener('keydown',e=>{
    if(e.key==='Shift'&&!e.ctrlKey&&!e.altKey&&!e.metaKey){
      if(!e.repeat)pressedAt=performance.now();
    }else clearTaps();
    if($('dialog').open){
      if(e.key==='Enter'&&document.activeElement===$('dialog input'))$('#results button')?.click();
      if(['ArrowDown','ArrowUp'].includes(e.key)){
        e.preventDefault();const buttons=[...document.querySelectorAll('#results button')];
        buttons[Math.max(0,Math.min(buttons.length-1,buttons.indexOf(document.activeElement)+(e.key==='ArrowDown'?1:-1)))]?.focus();
      }
      return;
    }
    if(!e.ctrlKey&&!e.metaKey)return;
    const action={k:'search',p:'search',b:'projectToggle',j:'panelToggle'}[e.key.toLowerCase()];
    if(action){e.preventDefault();$('#'+action).click()}
  });
}
init();
