^if($a){
	да
}
^if($a){
	$var
}
^if($a){
	^if($b){
		$var
	}
}

^if($a){
	<div>
		<ul>
			<li>
				<a href="index.html"><span>text</span></a>
				<br />
			</li>
			<li>
				<a href="index.html"><b>test</b></a><br>
			</li>
		</ul>
	</div>
}
<div>
</div>

^if($a){
	<div>
		<ul>
			<a><span>text</span></a>
			<br>
		</ul>
	</div>
}

^if(
	$a eq 'b'
	&& $c == 15 # комментарий
	|| $d ge 'asdf'
	|| (
		$a == 99
		|| $b eq "aa" # еще комментарий ^)
		|| (
			$c == 15
		)
	)
	|| $a eq ')'
	|| $a eq ")"
){
	да
	^if($a){
		b
		^if($c){
			ddd
			eee
			^switch[var]{
				^case[x]{

				}
				^case[Y]{
					^mail:send[
						$.from[robot]
						$.to[people]
						$.subject[]
						$.html[
							$.value{^html.base64[]}
							$.content-transfer-encoding[base64]
						]
						^if(!$sended){
							$sended[^table::create{
name	value
}]
						}
						^if($a){
							^if($b){
								^if(^uri.pos[/x] == 0){
									$id(0)
									$_tmp[^uri.match[/x/(\d+)(^$|\/)][i]{
										$id($match.1)
									}]
# comment
									^redirect[
										/x/^if($id){$id/};
										$.status(301)
									]

								}
							}
						}
					]
				}
			}
		}
	}
}{
	ytn
}

<td>
	<div>
		^if($aa){
			^if($bb){
				xxx
				^if(
					^int:sql{select count(*) from #a and ')'}
					|| $var
					|| ')' # asdf
					|| ")" # asdf
					|| $var eq ^)
				){

				}
			}
		}
	</div>
</td>



@main[]


# Тестовый файл для форматирования
@CLASS
User

@create[name;email]
$self.name[$name]
$self.email[$email]

@validate[]
^if(!$self.email){
	^throw[error;Email required]
}

# Метод с комментариями
@process[data]
# Обработка данных
^if(
	$data
	&& $self.validate[]
# проверка завершена
){
	^result[]
}

# Hash-комментарий в колонке 0
 # Это НЕ комментарий (таб перед #)

@main[]
^if($a){
	^if(
		^int:sql{select count(*) from #a and ')'}
		|| $var
		|| ')' # comment in expression
		|| (
			$a eq 'b' #line
		)
		|| ")" # another comment
		|| $var eq ^)
	){
		^process[]
	}
}

@method[]
 @not_method[]

# Тестовый файл для форматирования
@CLASS
User

@create[name;email]
$self.name[$name]
$self.email[$email]

@validate[]
^if(!$self.email){
	^throw[error;Email required]
}

# Метод с комментариями
@process[data]
# Обработка данных
^if(
	$data
	&& $self.validate[]
# проверка завершена
){
	^result[]
}

# Hash-комментарий в колонке 0
 # Это НЕ комментарий (таб перед #)

@main[]
^if($a){
	^if(
		^int:sql{select count(*) from #a and ')'}
		|| $var
		|| ')' # comment in expression
		|| ")" # another comment
		|| $var eq ^)
	){
		^process[]
	}
}
^if($a){
	^if(!-f $tabs_src_en){
		^rem{
			файла нет, проверяем есть ли index.html (работа со старым адресом)
			ч
				^rem{
						^rem{
							xxx
						}
						sdf
				}
		}
	}
}

^if($a){
# comment
	# no comment
	^if($b){
		# no comment
	}
	string
}

# s1
 # s2
^if($a){
# s3
	#s4
}