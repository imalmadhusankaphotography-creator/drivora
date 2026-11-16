// Screenshots at common head-unit sizes: node shot.js [outDir]
var puppeteer=require('puppeteer-core'),path=require('path');
var out=process.argv[2]||'/tmp';
var sizes=[[1024,600],[1280,720],[1920,720],[800,480],[1024,768],[768,1024],[1080,1920],[412,915]];
(async function(){
  var b=await puppeteer.launch({executablePath:'/usr/bin/google-chrome',args:['--no-sandbox','--disable-gpu']}),errs=[];
  var seed=JSON.stringify({v:1,cars:[{id:'c1',make:'Toyota',model:'Vitz',year:'2008',plate:'CAB-1234',body:'hatch',dial:180,acc:'cyan',odo:84210,service:90000,kmpl:16,corr:0}],cur:'c1',trip:{dist:0,moving:0,max:0,smooth:0},set:{unit:'kmh',src:'auto',limit:70,beep:0,theme:'day',price:0,cur:'Rs',obd:null,acc:'cyan',map:'trail'},places:[{n:'Home',q:'Hatton'},{n:'Work',q:'Laxapana'}],recent:[]});
  for(var i=0;i<sizes.length;i++){
    var p=await b.newPage();await p.setViewport({width:sizes[i][0],height:sizes[i][1],deviceScaleFactor:1});
    p.on('pageerror',function(e){errs.push(e.message);});
    await p.evaluateOnNewDocument(function(seed){window.DRIVORA_TEST=[];localStorage.setItem('drivora.v1',seed);localStorage.removeItem('drivora.music.v1');},seed);
    await p.goto('file://'+path.join(__dirname,'../www/index.html')+'#demo');
    await new Promise(function(r){setTimeout(r,5000);});
    var ov=await p.evaluate(function(){var bad=[],els=document.querySelectorAll('.card,.dock,.strip,.dial,.top'),i,r;for(i=0;i<els.length;i++){r=els[i].getBoundingClientRect();if(r.right>innerWidth+1||r.left<-1||(!document.body.classList.contains('portrait')&&(r.bottom>innerHeight+1||r.top<-1)))bad.push(els[i].className+' '+Math.round(r.left)+','+Math.round(r.top)+','+Math.round(r.right)+','+Math.round(r.bottom));}return {cls:document.body.className,bad:bad,sw:document.documentElement.scrollWidth};});
    console.log(sizes[i].join('x'),'['+ov.cls+']',ov.bad.length?'OUTSIDE SCREEN: '+ov.bad.join(' | '):'all panels inside the screen');
    await p.screenshot({path:path.join(out,'L_'+sizes[i].join('x')+'.png')});await p.close();
  }
  console.log(errs.length?errs.join('\n'):'no page errors');await b.close();
})();
