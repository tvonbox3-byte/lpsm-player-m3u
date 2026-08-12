import test from 'node:test'; import assert from 'node:assert/strict';
import { passwordHash,passwordMatches,signToken,verifyToken } from '../src/auth.js';
test('hash valida senha sem armazenar texto puro',()=>{const h=passwordHash('segredo');assert.equal(passwordMatches('segredo',h),true);assert.equal(passwordMatches('errada',h),false);assert.equal(h.includes('segredo'),false);});
test('token assinado detecta adulteração',()=>{const t=signToken({role:'admin'},'secret');assert.equal(verifyToken(t,'secret').role,'admin');assert.equal(verifyToken(t+'x','secret'),null);});
