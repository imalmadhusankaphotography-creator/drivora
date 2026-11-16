var fs=require('fs'),acorn=require('acorn');
var s=fs.readFileSync(process.argv[2],'utf8');
var m=/<script>([\s\S]*?)<\/script>/.exec(s);
try{acorn.parse(m[1],{ecmaVersion:5});console.log('ES5 syntax OK,',m[1].split('\n').length,'lines');}catch(e){console.log('ES5 FAIL',e.message);process.exit(1);}
