@main[]
<style>
	.item {
		font-weight: bold;
		opacity: 1;
		color: red;
	}
	.aabbcc {
		font-size: 15px;
		font-weight: bold;
	}
	.abc {

	}
	.item {
		color: $color;
		font-weight: bold;
		^styleMethod[]
	}
	.list {
		^data.foreach[k;v]{
			.list-item-$k {
				color: $v.color;
			}
		}
		^method[
			.no-css {
			}
		]
		^method_inner[
			$.var{
				.x-n-css {
					font-weight: bold^;
				}
			}
		]
		^if($a || $b){
			.class {

			}
		}
		^method(
			.css-no {
			}
		)
		$var{
			.x1 {
			}
		}
		$var {
			.x1 {
			}
		}
		$var[
			.x2 {
			}
		]
		$var(
			.x3 {
			}
		)
	}
</style>

<script>
	var list = [];
	list.fo
</script>

<div class="item" style="font-weight: bold; font-size: 24px;">
	<div class="item abc">
		<td>^if(def $var){x;y}</td>
		^list.menu{
			<tr>
				<td>^if($a || $b){...}</td>
				<td>^if(def $list.url){<a href="$list.url">^taint[as-is][$list.title]</a>;^taint[as-is][$list.title]}: $list.role</td>}
			</tr>
		}
	</div>
</div>

<div>
	<strong class="aabbcc item asdfklj haskldjf asd"></strong>
</div>