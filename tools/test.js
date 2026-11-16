var puppeteer=require('puppeteer-core'),path=require('path');
var fails=0;function ok(c,m){console.log((c?'PASS ':'FAIL ')+m);if(!c)fails++;}
function wait(ms){return new Promise(function(r){setTimeout(r,ms);});}
(async function(){
  var b=await puppeteer.launch({executablePath:'/usr/bin/google-chrome',args:['--no-sandbox','--disable-gpu']});
  var url='file://'+path.join(__dirname,'../www/index.html'),errs=[];
  var p=await b.newPage();await p.setViewport({width:1024,height:600});
  p.on('pageerror',function(e){errs.push(e.message);});
  p.on('console',function(m){if(m.type()==='error')errs.push(m.text());});
  await p.evaluateOnNewDocument(function(){window.DRIVORA_TEST=[];});
  await p.goto(url);await p.evaluate(function(){localStorage.clear();});await p.reload();await wait(900);
  // first run: vehicle form opens
  ok(await p.$eval('#shCar',function(e){return e.classList.contains('show');}),'first run opens Add vehicle');
  await p.screenshot({path:'/tmp/t_form.png'});
  await p.type('#fMake','Toyota');await p.type('#fModel','Vitz');await p.type('#fYear','2008');await p.type('#fPlate','cab-1234');
  await p.click('#fDial button[data-v="180"]');
  await p.type('#fOdo','84210');await p.type('#fService','90000');await p.type('#fKmpl','16');await p.type('#fCorr','3');
  await p.click('#fSave');await wait(300);
  ok(await p.$eval('#vName',function(e){return e.textContent;})==='Toyota Vitz','vehicle saved and shown');
  ok(await p.evaluate(function(){return JSON.parse(localStorage.getItem('drivora.v1')).cars[0].plate;})==='CAB-1234','persisted to storage');
  // feed: GPS-like samples, 60 km/h for 60 s => 1 km; correction +3% => shows 62
  var r=await p.evaluate(function(){var D=DRIVORA,t=Date.now()-61000,i;D.live.gps='ok';
    for(i=0;i<=60;i++)D.feed('gps',60,t+i*1000);return {dist:D.state.trip.dist,odo:D.state.cars[0].odo,max:D.state.trip.max};});
  ok(Math.abs(r.dist-1)<0.001,'60 km/h for 60 s = 1.000 km (got '+r.dist.toFixed(4)+')');
  ok(Math.abs(r.odo-84211)<0.001,'odometer advanced by 1 km');
  await wait(1500);
  ok(await p.$eval('#spd',function(e){return e.textContent;})==='62','speedometer correction +3% shows 62 (got '+await p.$eval('#spd',function(e){return e.textContent;})+')');
  // limit warning
  await p.evaluate(function(){DRIVORA.command('limit 50');});await wait(400);
  ok(await p.evaluate(function(){return document.body.classList.contains('over');}),'over-limit state when 62 > 50');
  await p.evaluate(function(){DRIVORA.command('limit off');});await wait(300);
  ok(!(await p.evaluate(function(){return document.body.classList.contains('over');})),'limit off clears warning');
  // commands: destination is locked first, guidance starts on "start"
  ok(await p.evaluate(function(){return DRIVORA.command('go to Kandy');})==='nav','"go to Kandy" locks the destination');
  ok(await p.$eval('#bTitle',function(e){return e.textContent;})==='Kandy','banner shows destination');
  ok(await p.evaluate(function(){return DRIVORA_TEST.length;})===0,'Google Maps is not opened until start');
  ok(await p.$eval('#bStart',function(e){return !e.classList.contains('hide');}),'Start button shown on the banner');
  ok(await p.evaluate(function(){return DRIVORA.command('start');})==='start','"start" begins guidance');
  ok(await p.evaluate(function(){return DRIVORA_TEST[0];})==='https://www.google.com/maps/dir/?api=1&destination=Kandy&travelmode=driving&dir_action=navigate','Google Maps navigation URL built');
  await p.type('#dest','Nuwara Eliya');await p.click('#destGo');
  ok(await p.$eval('#bTitle',function(e){return e.textContent;})==='Nuwara Eliya','typed destination is locked');
  await p.click('#bStart');
  ok(await p.evaluate(function(){return DRIVORA_TEST.length===2&&DRIVORA_TEST[1].indexOf('Nuwara%20Eliya')>0;}),'Start button begins guidance');
  await p.click('#bEnd');
  ok(await p.$eval('#bTitle',function(e){return e.textContent;})==='No destination','End button clears the destination');
  var cm=await p.evaluate(function(){var C=DRIVORA.command,T=function(){return document.getElementById('bTitle').textContent;},r={};
    r.lock=C('lock destination to Galle')+'|'+T();r.polite=C('Hey assistant, please go to Ella.')+'|'+T();
    r.singlish=C('kandy walata yamu')+'|'+T();r.sinhala=C('මහනුවරට යමු')+'|'+T();
    r.sinhala2=C('කොළඹ වලට යන්න ඕන')+'|'+T();
    r.end=C('cancel navigation');r.startNone=C('start');
    r.speedSi=C('වේගය කීයද');r.speedSing=C('speed eka keeyada');r.speed=C("what's my speed");
    r.time=C('what time is it')+'|'+document.getElementById('jMsg').textContent;r.fuel=C('how much fuel is left');r.map=C('show map');r.trail=C('show trail');C('show map');
    r.nextSi=C('ඊළඟ සින්දුව');r.pauseSi=C('සින්දු නවත්තන්න');
    r.unknown=C('blah blah blah')+'|'+T();r.trip=C('reset trip')+'|'+DRIVORA.state.trip.dist;r.hud=C('hud');return r;});
  ok(cm.lock==='nav|Galle','"lock destination to Galle" ('+cm.lock+')');
  ok(cm.polite==='nav|Ella','polite phrasing keeps the place name ('+cm.polite+')');
  ok(cm.singlish==='nav|kandy','Singlish "kandy walata yamu" ('+cm.singlish+')');
  ok(cm.sinhala==='nav|මහනුවර','Sinhala "mahanuwarata yamu" locks the place ('+cm.sinhala+')');
  ok(cm.sinhala2==='nav|කොළඹ','Sinhala "kolamba walata yanna ona" ('+cm.sinhala2+')');
  ok(cm.end==='navend'&&cm.startNone==='unknown','cancel navigation; "start" without a destination is not guidance');
  ok(cm.speedSi==='speed'&&cm.speedSing==='speed'&&cm.speed==='speed','speed question in Sinhala, Singlish and English');
  ok(/^time\|It is \d{1,2}:\d\d (AM|PM)\.$/.test(cm.time),'time question ('+cm.time+')');
  ok(cm.fuel==='fuel'&&cm.map==='map'&&cm.trail==='trail','fuel, show map, show trail');
  ok(cm.nextSi==='next'&&cm.pauseSi==='pause','Sinhala next / pause');
  ok(cm.unknown==='unknown|No destination','unknown words do not set a destination');
  ok(cm.trip==='trip|0'&&cm.hud==='hud','reset trip and hud commands');
  await wait(300);await p.screenshot({path:'/tmp/t_hud.png'});
  await p.evaluate(function(){DRIVORA.command('exit hud');});
  // OBD: simulated ELM327 adapter
  var o=await p.evaluate(function(){return new Promise(function(done){
    var D=DRIVORA,log=[];
    var answers={'ATZ':'\r\rELM327 v1.5\r\r','ATE0':'ATE0\rOK\r\r','ATL0':'OK\r\r','ATS0':'OK\r\r','ATH0':'OK\r\r','ATSP0':'OK\r\r',
      '0100':'SEARCHING...\r4100BE3FA813\r\r','0120':'412000020000\r\r','010D':'410D3C\r\r','010C':'410C1AF8\r\r','0105':'41057B\r\r','012F':'412F80\r\r','0104':'410480\r\r','0101':'41018207E500\r\r','ATRV':'13.8V\r\r'};
    D.state.set.src='auto';
    D.obdAttach(function(s){var c=s.replace('\r','');log.push(c);setTimeout(function(){var a=answers[c]||'NO DATA\r\r',h=Math.floor(a.length/2);D.obdRx(a.substring(0,h));D.obdRx(a.substring(h)+'>');},2);},'Mock ELM');
    setTimeout(function(){done({state:D.obd.state,sup0D:!!D.obd.sup[0x0D],sup2F:!!D.obd.sup[0x2F],rpm:D.live.rpm,cool:D.live.cool,fuel:D.live.fuel,volt:D.live.volt,mil:D.live.mil,dtc:D.live.dtc,load:D.live.load,src:D.live.src,target:D.live.target,log:log.slice(0,9).join(',')});},1500);
  });});
  ok(o.state==='live','OBD reaches live state ('+o.log+')');
  ok(o.sup0D&&o.sup2F,'supported PIDs decoded (speed, fuel level)');
  ok(o.src==='obd'&&o.target===60,'speed comes from OBD: 0x3C = 60 km/h');
  ok(o.rpm===1726,'RPM 0x1AF8/4 = 1726 (got '+o.rpm+')');
  ok(o.cool===83,'coolant 0x7B-40 = 83 C (got '+o.cool+')');
  ok(Math.round(o.fuel)===50,'fuel 0x80 = 50 % (got '+o.fuel+')');
  ok(o.volt===13.8,'battery 13.8 V');
  ok(o.mil===true&&o.dtc===2,'check engine on, 2 faults');
  await wait(1200);await p.screenshot({path:'/tmp/t_obd.png'});
  ok(await p.$eval('#stObd',function(e){return e.textContent;})==='OBD live','OBD pill shows live');
  // car does not answer
  var o2=await p.evaluate(function(){return new Promise(function(done){var D=DRIVORA;
    D.obdAttach(function(s){var c=s.replace('\r','');setTimeout(function(){D.obdRx((c==='0100'?'SEARCHING...\rUNABLE TO CONNECT\r\r':'OK\r\r')+'>');},2);},'Mock ELM');
    setTimeout(function(){done({state:D.obd.state,err:D.obd.err});},600);});});
  ok(o2.state==='init'&&/ignition/.test(o2.err),'ignition-off case explained: '+o2.err);
  await p.evaluate(function(){DRIVORA.obdDrop('');});
  ok(await p.evaluate(function(){return DRIVORA.live.rpm===null&&DRIVORA.obd.state==='off';}),'disconnect clears car values');
  // odd response shapes
  ok(await p.evaluate(function(){var f=DRIVORA.obdBytes;return f('41 0D 50 \r',1,'0D')[0]===80&&f('7E8 03 41 0D 28\r',1,'0D')[0]===40&&f('SEARCHING...\r',1,'0D')===null&&f('7E803410D28\r',1,'0D')[0]===40&&f('NO DATA',1,'0D')===null&&f(null,1,'0D')===null;}),'parser handles spaces, headers, NO DATA');
  // sunset for Colombo on 16 Nov ~ 12:20 UTC (17:50 local)
  var su=await p.evaluate(function(){return DRIVORA.sunUT(6.93,79.85,new Date(Date.UTC(2026,10,16,6)),false);});
  ok(su>12.1&&su<12.6,'sunset Colombo 16 Nov = '+su.toFixed(2)+' h UTC');
  var sr=await p.evaluate(function(){return DRIVORA.sunUT(6.93,79.85,new Date(Date.UTC(2026,10,16,6)),true);});
  ok(sr>0.3&&sr<0.9,'sunrise Colombo 16 Nov = '+sr.toFixed(2)+' h UTC');
  // settings + sheets open without error
  await p.click('.dk[data-go="settings"]');await wait(300);
  function acc(){return p.evaluate(function(){return getComputedStyle(document.documentElement).getPropertyValue('--accRgb').trim();});}
  var bx=await (await p.$('#cw')).boundingBox(),cx=bx.x+bx.width/2,cy=bx.y+bx.height/2,R=bx.width/2;
  await p.mouse.click(cx+R*0.97,cy);var c1=(await acc()).split(',');
  ok(+c1[0]===255&&+c1[1]<25&&+c1[2]<25,'colour circle: right edge = red ('+c1.join(',')+')');
  await p.mouse.click(cx-R*0.5*0.97,cy-R*0.866*0.97);var c2=(await acc()).split(',');
  ok(+c2[1]===255&&+c2[0]<25&&+c2[2]<25,'colour circle: 120 degrees = green ('+c2.join(',')+')');
  await p.mouse.click(cx,cy);var c3=(await acc()).split(',');
  ok(+c3[0]>245&&+c3[1]>245&&+c3[2]>245,'colour circle: centre = white ('+c3.join(',')+')');
  await p.mouse.move(cx+R*0.2,cy+R*0.2);await p.mouse.down();await p.mouse.move(cx+R*0.3,cy+R*0.75,{steps:6});await p.mouse.up();
  var c4=await acc();
  ok(await p.evaluate(function(){var s=JSON.parse(localStorage.getItem('drivora.v1'));return s.cars[0].acc+'|'+s.set.acc;})===c4+'|'+c4,'dragged colour saved to the vehicle ('+c4+')');
  ok(await p.evaluate(function(){var h=DRIVORA.rgb2hs(31,208,240),c=DRIVORA.hsv2rgb(200,1);return Math.round(h[0])===189&&c.join(',')==='0,170,255';}),'colour maths round trip');
  await p.screenshot({path:'/tmp/t_settings.png'});
  await p.click('#sAcc button[data-v="255,45,70"]');
  ok((await acc())==='255,45,70','preset swatch applies red');
  ok(await p.evaluate(function(){return DRIVORA.command('theme purple')==='theme';})&&(await acc())==='170,110,255','"theme purple" command');
  await p.click('#sAcc button[data-v="31,208,240"]');
  await p.click('#sUnit button[data-v="mph"]');await wait(1300);
  ok(await p.$eval('#unit',function(e){return e.textContent;})==='mph','unit switch to mph');
  await p.click('#sUnit button[data-v="kmh"]');
  await p.click('#shSettings [data-close]');
  await p.click('#stObd');await wait(200);await p.screenshot({path:'/tmp/t_obdsheet.png'});
  ok(await p.$eval('#oNoApp',function(e){return !e.classList.contains('hide');}),'OBD sheet explains preview has no Bluetooth');
  await p.click('#shObd [data-close]');
  // Google map addresses
  var mu=await p.evaluate(function(){var D=DRIVORA;D.command('end navigation');D.live.lat=null;D.state.pos=null;var a=D.mapUrl();D.state.pos={lat:6.9271,lon:79.8612};var b=D.mapUrl();D.navigate('Kandy');var c=D.mapUrl();D.live.lat=7.2906;D.live.lon=80.6337;var d=D.mapUrl();D.live.lat=null;return [a,b,c,d];});
  ok(mu[0]==='','map: no address before a position is known');
  ok(mu[1]==='https://maps.google.com/maps?q=6.92710,79.86120&z=16&output=embed','map: centres on last known position');
  ok(mu[2]==='https://maps.google.com/maps?saddr=6.92710,79.86120&daddr=Kandy&output=embed','map: route to destination');
  ok(mu[3].indexOf('saddr=7.29060,80.63370')>0,'map: live GPS position wins over the saved one');
  ok(await p.$eval('#gmap',function(e){return !e.classList.contains('hide');}),'map panel visible when online with a position');
  await p.click('#mapSw');ok(await p.$eval('#gmap',function(e){return e.classList.contains('hide');}),'switch to trail hides the map');await p.click('#mapSw');
  // music: add files, play, keep after restart
  var fs=require('fs');function wav(sec){var n=8000*sec,b=Buffer.alloc(44+n,128);b.write('RIFF',0);b.writeUInt32LE(36+n,4);b.write('WAVEfmt ',8);b.writeUInt32LE(16,16);b.writeUInt16LE(1,20);b.writeUInt16LE(1,22);b.writeUInt32LE(8000,24);b.writeUInt32LE(8000,28);b.writeUInt16LE(1,32);b.writeUInt16LE(8,34);b.write('data',36);b.writeUInt32LE(n,40);return b;}
  fs.writeFileSync('/tmp/Sanda Mithuru.wav',wav(3));fs.writeFileSync('/tmp/Ran Tharu.wav',wav(2));fs.writeFileSync('/tmp/notes.txt','x');
  await p.click('.dk[data-go="music"]');await wait(200);
  await (await p.$('#mFiles')).uploadFile('/tmp/Sanda Mithuru.wav','/tmp/Ran Tharu.wav','/tmp/notes.txt');await wait(900);
  var m1=await p.evaluate(function(){var m=DRIVORA.music();return {n:m.tracks.length,kinds:m.tracks.map(function(t){return t.kind;}).join(','),idx:m.idx,src:(m.src||'').substring(0,5),title:document.getElementById('mTitle').textContent};});
  ok(m1.n===2&&m1.kinds==='idb,idb','two songs added and stored, text file ignored ('+m1.kinds+')');
  ok(m1.idx===0&&m1.src==='blob:'&&m1.title==='Sanda Mithuru','first added song is loaded');
  await p.screenshot({path:'/tmp/t_music.png'});
  await p.click('#shMusic [data-close]');await p.click('#mNext');await wait(300);
  ok(await p.$eval('#mTitle',function(e){return e.textContent;})==='Ran Tharu','next song button');
  await p.evaluate(function(){DRIVORA.command('pause');});
  await p.reload();await wait(1200);
  var m2=await p.evaluate(function(){var m=DRIVORA.music();return {n:m.tracks.length,idx:m.idx,src:m.src,title:document.getElementById('mTitle').textContent,sub:document.getElementById('mSub').textContent};});
  ok(m2.n===2&&m2.idx===1&&m2.title==='Ran Tharu','after restart the list and the current song are still there ('+m2.n+' songs, "'+m2.title+'")');
  await p.click('#mPlay');await wait(800);
  var m3=await p.evaluate(function(){var m=DRIVORA.music(),a=document.getElementById('audio');return {src:(m.src||'').substring(0,5),dur:a.duration,err:a.error?a.error.code:0};});
  ok(m3.src==='blob:'&&Math.abs(m3.dur-2)<0.1&&!m3.err,'after restart the stored song plays again (duration '+m3.dur+' s)');
  await p.click('.dk[data-go="music"]');await wait(200);await p.type('#mFind','sanda');await wait(200);
  ok(await p.$$eval('#mList .rowl',function(r){return r.length;})===1,'song search filters the list');
  await p.click('#mList [data-x]');await wait(300);
  ok(await p.evaluate(function(){var m=DRIVORA.music();return m.tracks.length===1&&m.tracks[0].name==='Ran Tharu'&&m.idx===0&&JSON.parse(localStorage.getItem('drivora.music.v1')).list.length===1;}),'remove one song keeps the other and the current one');
  await p.click('#shMusic [data-close]');
  // voice: mocked speech service
  await p.evaluate(function(){window._spoken=[];window._q=['what is my speed','මහනුවරට යමු','start','play ran',''];
    window.Capacitor={Plugins:{Voice:{listen:function(o){window._lang=o.lang;var t=window._q.shift();return t?Promise.resolve({text:t}):Promise.reject({message:'I did not hear anything. Tap the mic and try again.'});},speak:function(o){window._spoken.push(o.text);return Promise.resolve();},stop:function(){return Promise.resolve();}}}};
    DRIVORA.state.set.talk=1;DRIVORA.state.set.vlang='si-LK';DRIVORA.feed('gps',50,Date.now()-1000);DRIVORA.feed('gps',50,Date.now());});
  await wait(1500);var n0=await p.evaluate(function(){return DRIVORA_TEST.length;});
  await p.click('#mic');await wait(300);
  var v1=await p.evaluate(function(){return {lang:window._lang,last:window._spoken[window._spoken.length-1],heard:document.getElementById('jHeard').textContent};});
  ok(v1.lang==='si-LK','mic passes the chosen voice language');
  ok(/^You are doing 5\d kilometres per hour\.$/.test(v1.last),'voice "what is my speed" is answered aloud: '+v1.last);
  ok(v1.heard.indexOf('what is my speed')>=0,'what was heard is shown');
  await p.click('#orb');await wait(300);
  ok(await p.$eval('#bTitle',function(e){return e.textContent;})==='මහනුවර'&&(await p.evaluate(function(){return DRIVORA_TEST.length;}))===n0,'voice in Sinhala locks the destination without leaving the app');
  await p.click('#mic');await wait(300);
  ok((await p.evaluate(function(){return DRIVORA_TEST.length;}))===n0+1,'voice "start" begins guidance');
  await p.click('#mic');await wait(600);
  var v2=await p.evaluate(function(){var m=DRIVORA.music();return {last:window._spoken[window._spoken.length-1],src:(m.src||'').substring(0,5),vol:document.getElementById('audio').volume};});
  ok(v2.last==='Playing Ran Tharu.'&&v2.src==='blob:','voice "play ran" finds and plays the song ('+v2.last+')');
  ok(v2.vol===1,'music volume restored after listening');
  var ns=await p.evaluate(function(){return window._spoken.length;});
  await p.click('#mic');await wait(300);
  ok(await p.$eval('#jMsg',function(e){return e.textContent;})==='I did not hear anything. Tap the mic and try again.'&&(await p.evaluate(function(){return window._spoken.length;}))===ns,'nothing heard: shown on screen, not spoken');
  await p.evaluate(function(){DRIVORA.state.set.talk=0;DRIVORA.command('time');});
  ok((await p.evaluate(function(){return window._spoken.length;}))===ns,'spoken replies off stays silent');
  await p.evaluate(function(){DRIVORA.command('pause');DRIVORA.endNav();});
  await p.screenshot({path:'/tmp/t_assistant.png'});
  console.log(errs.length?'ERRORS:\n'+errs.join('\n'):'no page errors');
  console.log(fails?fails+' FAILED':'ALL PASSED');
  await b.close();process.exit(fails||errs.length?1:0);
})();
