// Builds www/index.html from src/index.src.html (embeds the fonts so the app works offline).
var fs=require('fs'),path=require('path');
var root=path.join(__dirname,'..');
var fdir=path.join(__dirname,'fonts');
var s=fs.readFileSync(path.join(root,'src/index.src.html'),'utf8');
function b64(f){return fs.readFileSync(path.join(fdir,f)).toString('base64');}
s=s.replace('/*FONT_500*/',b64('barlow-condensed-latin-500-normal.woff2')).replace('/*FONT_600I*/',b64('barlow-condensed-latin-600-italic.woff2'));
s=s.replace('/*LOGO*/',fs.readFileSync(path.join(root,'native/brand/logo-112.png')).toString('base64'));
fs.mkdirSync(path.join(root,'www'),{recursive:true});
fs.writeFileSync(path.join(root,'www/index.html'),s);
console.log('www/index.html',Math.round(s.length/1024)+' KB');
